package com.wangzan101.mika.core.shell

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class IsolatedKeyboardProbeTest {
    private val target = IsolatedKeyboardProbe.Target(35, 7, "virtual:mika:one", true)
    private class FakeIo : IsolatedKeyboardProbe.Io {
        val events = mutableListOf<String>()
        val packets = mutableListOf<ByteArray>()
        var registrationMatches = true
        val routes = ArrayDeque<Boolean>()
        var failAt: String? = null
        var failRemoveOnce = false
        private fun event(name: String) { events += name; if (failAt == name) throw IOException(name) }
        override fun associate(uniqueId: String) { event("associate:$uniqueId") }
        override fun create() { event("create") }
        override fun awaitRouting(target: IsolatedKeyboardProbe.Target): Boolean { event("await"); return registrationMatches }
        override fun isRouted(target: IsolatedKeyboardProbe.Target): Boolean { event("route"); return routes.removeFirstOrNull() ?: true }
        override fun write(packet: ByteArray) { event("write"); packets += packet }
        override fun closeDevice() { event("close") }
        override fun removeAssociation() {
            event("remove")
            if (failRemoveOnce) { failRemoveOnce = false; throw IOException("cleanup") }
        }
    }

    @Test fun disabledByDefaultDoesNotTouchBackend() {
        val io = FakeIo()
        IsolatedKeyboardProbe(target, io).use { assertFalse(it.start()); assertFalse(it.stroke(4)) }
        assertTrue(io.events.isEmpty())
    }

    @Test fun rejectsPrimaryScreenOldAndroidMissingIdentityAndMissingIsolationBeforeMutation() {
        for (invalid in listOf(target.copy(displayId = 0), target.copy(displayId = -1), target.copy(sdk = 34),
            target.copy(uniqueId = " "), target.copy(focusIsolated = false))) {
            val io = FakeIo()
            IsolatedKeyboardProbe(invalid, io, true).use { assertFalse(it.start()) }
            assertTrue(io.events.isEmpty())
        }
    }

    @Test fun associatesBeforeDeviceCreationAndClosesBeforeUnbinding() {
        val io = FakeIo()
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertTrue(probe.start())
        assertFalse(probe.start())
        probe.close(); probe.close()
        assertEquals(listOf("associate:virtual:mika:one", "create", "await", "close", "remove"), io.events)
        assertFalse(probe.stroke(4))
    }

    @Test fun incorrectRegistrationClosesWithoutAnyInputOrFallback() {
        val io = FakeIo().apply { registrationMatches = false }
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertFalse(probe.start())
        assertFalse(probe.stroke(4))
        assertTrue(io.packets.isEmpty())
        assertEquals(listOf("close", "remove"), io.events.takeLast(2))
    }

    @Test fun aLostAssociationReceiptStillAttemptsUnbind() {
        val io = FakeIo().apply { failAt = "associate:virtual:mika:one" }
        assertFalse(IsolatedKeyboardProbe(target, io, true).start())
        assertEquals(listOf("associate:virtual:mika:one", "remove"), io.events)
    }

    @Test fun partialDeviceCreationStillClosesAndUnbinds() {
        val io = FakeIo().apply { failAt = "create" }
        assertFalse(IsolatedKeyboardProbe(target, io, true).start())
        assertEquals(listOf("close", "remove"), io.events.takeLast(2))
    }

    @Test fun routeLossBeforeDownNeverWritesAndClosesDevice() {
        val io = FakeIo().apply { routes.add(false) }
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertTrue(probe.start()); assertFalse(probe.stroke(4))
        assertTrue(io.packets.isEmpty())
        assertEquals(listOf("close", "remove"), io.events.takeLast(2))
    }

    @Test fun routeLossWithKeyHeldClosesInsteadOfSendingReleaseToAnotherDisplay() {
        val io = FakeIo().apply { routes.addAll(listOf(true, false)) }
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertTrue(probe.start()); assertFalse(probe.stroke(4))
        assertEquals(1, io.packets.size)
        assertEquals(listOf("close", "remove"), io.events.takeLast(2))
    }

    @Test fun inputFailurePreventsLaterKeystrokesAndCleansUp() {
        val io = FakeIo().apply { failAt = "write" }
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertTrue(probe.start()); assertFalse(probe.stroke(4)); assertFalse(probe.stroke(5))
        assertEquals(1, io.events.count { it == "write" })
        assertEquals(listOf("close", "remove"), io.events.takeLast(2))
    }

    @Test fun failedDeviceCloseKeepsItsMappingUntilTheDeviceIsConfirmedClosed() {
        val io = FakeIo().apply { failAt = "close" }
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertTrue(probe.start())
        try { probe.close(); fail("close must expose failure") } catch (_: IOException) { }
        assertFalse(io.events.contains("remove"))
        assertFalse(probe.stroke(4))
        io.failAt = null
        probe.close()
        assertEquals(listOf("close", "remove"), io.events.takeLast(2))
    }

    @Test fun cleanupFailureIsRecordedAndCanBeRetried() {
        val io = FakeIo().apply { registrationMatches = false; failRemoveOnce = true }
        val probe = IsolatedKeyboardProbe(target, io, true)
        assertFalse(probe.start())
        assertEquals(1, probe.failure!!.suppressed.size)
        probe.close()
        assertEquals(1, io.events.count { it == "close" })
        assertEquals(2, io.events.count { it == "remove" })
    }

    @Test fun writeHasBootKeyboardDownAndReleaseEventsAndRejectsPowerUsage() {
        val io = FakeIo()
        IsolatedKeyboardProbe(target, io, true).use {
            assertTrue(it.start())
            assertFalse(it.stroke(0x66)); assertFalse(it.stroke(3)); assertFalse(it.stroke(4, 256))
            assertTrue(it.stroke(4, 2))
        }
        assertEquals(2, io.packets.size)
        val down = ByteBuffer.wrap(io.packets[0]).order(ByteOrder.nativeOrder())
        assertEquals(12, down.int); assertEquals(8, down.short.toInt())
        assertArrayEquals(byteArrayOf(2, 0, 4, 0, 0, 0, 0, 0), ByteArray(8).also(down::get))
        assertArrayEquals(ByteArray(8), io.packets[1].copyOfRange(6, 14))
    }

    @Test fun createEventUsesLinuxPackedOffsetsAndTheSameInputPort() {
        val bytes = IsolatedKeyboardProbe.createPacket("mika-test", "mika:port")
        val packet = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        assertEquals(11, packet.int)
        assertEquals("mika-test", bytes.copyOfRange(4, 13).toString(Charsets.US_ASCII))
        assertEquals("mika:port", bytes.copyOfRange(132, 141).toString(Charsets.US_ASCII))
        packet.position(260)
        assertEquals(bytes.size - 280, packet.short.toInt())
        assertEquals(6, packet.short.toInt())
        assertTrue(bytes.copyOfRange(264, 280).all { it == 0.toByte() })
        assertEquals(0xc0.toByte(), bytes.last())
    }
}
