package com.wangzan101.mika.task

import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RootPhoneConnectorTest {
    @Test fun unavailableWindowCapabilityFailsAfterInitialization() = runBlocking {
        var initialized = false
        var connected = false
        try {
            connectRootPhone(100, 200, { initialized = true }, {
                assertTrue(initialized)
                null
            }, { connected = true })
            fail("missing window capability must fail")
        } catch (_: RootWindowUnavailable) { }
        assertTrue(initialized)
        assertFalse(connected)
    }

    @Test fun providedBackendIsUsedByRootScreen() = runBlocking {
        val target = VisualTarget(5, 7, "example.app", "window")
        var launched = false
        var connected = false
        val backend = object : RootVisualBackend {
            override suspend fun launch(packageName: String): Boolean { launched = true; return true }
            override suspend fun target() = target
            override suspend fun frame() = VisualFrame(target, 2, byteArrayOf(1))
            override suspend fun dispatch(target: VisualTarget, input: VisualInput) = VisualDispatch.NotSent
            override suspend fun listApps() = emptyList<Pair<String, String>>()
            override fun nowNanos() = 1L
        }
        val screen = connectRootPhone(100, 200, {}, { backend }, { connected = true })
        assertTrue(connected)
        assertTrue(screen.openApp("example.app") is ActionResult.Ok)
        assertTrue(launched)
        assertNotNull(screen.screenshotPng())
        assertEquals(ActionResult.Failed("input_not_sent"), screen.tapXY(1, 1))
    }

    @Test fun initializationFailureDoesNotRequestBackend() = runBlocking {
        var called = false
        try {
            connectRootPhone(100, 200, { throw IllegalStateException("failed") }, { called = true; null })
            fail("initialization failure must propagate")
        } catch (_: IllegalStateException) { }
        assertFalse(called)
    }

    @Test fun deathMonitoringFailureDoesNotReturnUsableScreen() = runBlocking {
        val backend = object : RootVisualBackend {
            override suspend fun launch(packageName: String) = false
            override suspend fun target(): VisualTarget? = null
            override suspend fun frame(): VisualFrame? = null
            override suspend fun dispatch(target: VisualTarget, input: VisualInput) = VisualDispatch.NotSent
            override suspend fun listApps() = emptyList<Pair<String, String>>()
            override fun nowNanos() = 0L
        }
        try {
            connectRootPhone(100, 200, {}, { backend }, { throw IllegalStateException("binder dead") })
            fail("dead helper must not produce a screen")
        } catch (_: IllegalStateException) { }
    }
}
