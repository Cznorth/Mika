package com.wangzan101.mika.core.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opt-in prototype, not connected to phone tools or the task runner. It emits HID
 * keystrokes, not Unicode text. The phone's IME still performs text composition.
 * Routing checks are necessary but are not proof of correct routing on an OEM ROM.
 */
class IsolatedKeyboardProbe(
    private val target: Target,
    private val io: Io,
    private val enabled: Boolean = false,
) : AutoCloseable {
    data class Target(val sdk: Int, val displayId: Int, val uniqueId: String, val focusIsolated: Boolean)

    interface Io {
        /** Associate BEFORE creating the device, so it never intentionally starts on display 0. */
        fun associate(uniqueId: String)
        fun create()
        fun awaitRouting(target: Target): Boolean
        /** Recheck device association AND display identity before each report. */
        fun isRouted(target: Target): Boolean
        fun write(packet: ByteArray)
        fun closeDevice()
        fun removeAssociation()
    }

    var failure: Exception? = null
        private set
    private var started = false
    private var ready = false
    private var associationAttempted = false
    private var deviceAttempted = false

    @Synchronized
    fun start(): Boolean {
        if (started) return false
        started = true
        if (!enabled || target.sdk < 35 || target.displayId <= 0 || target.uniqueId.isBlank() || !target.focusIsolated) return false
        return attempt {
            // A Binder call can take effect and then throw. Cleanup must still try to undo it.
            associationAttempted = true
            io.associate(target.uniqueId)
            deviceAttempted = true
            io.create()
            check(io.awaitRouting(target)) { "UHID device is not isolated to the requested display" }
            ready = true
        }
    }

    /** One boot-keyboard stroke. No focus changes, clipboard access, IME switching or main-display fallback. */
    @Synchronized
    fun stroke(usage: Int, modifiers: Int = 0): Boolean {
        if (!ready || usage !in 4..0x65 || modifiers !in 0..255) return false
        return attempt {
            check(io.isRouted(target)) { "UHID route changed before key down" }
            io.write(inputPacket(byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0)))
            check(io.isRouted(target)) { "UHID route changed before key up" }
            io.write(inputPacket(ByteArray(8)))
        }
    }

    private fun attempt(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (e: Exception) {
        failure = e
        if (e is InterruptedException) Thread.currentThread().interrupt()
        try { close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
        false
    }

    /** Close first (releases held keys), then unbind. Failed cleanup can be retried by calling close again. */
    @Synchronized
    override fun close() {
        started = true
        ready = false
        var error: Exception? = null
        if (deviceAttempted) {
            try { io.closeDevice(); deviceAttempted = false } catch (e: Exception) { error = e }
        }
        // If close failed, keep the mapping until close can be retried. Removing it
        // with a live keyboard could route held-key repeats onto the main display.
        if (associationAttempted && !deviceAttempted) {
            try { io.removeAssociation(); associationAttempted = false } catch (e: Exception) {
                if (error == null) error = e else error.addSuppressed(e)
            }
        }
        error?.let { throw it }
    }

    companion object {
        // Linux uapi/linux/uhid.h packed CREATE2 and INPUT2 wire layouts.
        // Descriptor: keyboard modifiers, reserved byte, six usages; no LED output reports.
        private val descriptor = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x05, 0x07,
            0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xc0,
        ).map(Int::toByte).toByteArray()

        fun createPacket(name: String, port: String): ByteArray {
            require(name.isNotEmpty() && name.length <= 127 && name.all { it.code in 33..126 })
            require(port.isNotEmpty() && port.length <= 63 && port.all { it.code in 33..126 })
            return ByteBuffer.allocate(280 + descriptor.size).order(ByteOrder.nativeOrder()).apply {
                putInt(11) // UHID_CREATE2
                put(name.toByteArray(Charsets.US_ASCII))
                position(132); put(port.toByteArray(Charsets.US_ASCII))
                position(260); putShort(descriptor.size.toShort()); putShort(0x06) // BUS_VIRTUAL
                putInt(0); putInt(0); putInt(0); putInt(0)
                put(descriptor)
            }.array()
        }

        fun inputPacket(report: ByteArray): ByteArray {
            require(report.size == 8) { "Only boot keyboard reports are supported" }
            return ByteBuffer.allocate(14).order(ByteOrder.nativeOrder()).putInt(12).putShort(8).put(report).array()
        }
    }
}
