package com.wangzan101.mika.core.uistate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootVisualScreenTest {
    private class Backend : RootVisualBackend {
        var current = VisualTarget(4, 20, "example.app", "window-1")
        var frameTarget = current
        var frameTime = 101L
        var now = 100L
        var afterProbe: (() -> Unit)? = null
        var dispatched = 0
        var outcome = VisualDispatch.Sent
        var duringDispatch: (() -> Unit)? = null
        override suspend fun launch(packageName: String) = true
        override suspend fun target(): VisualTarget {
            val result = current
            afterProbe?.also { afterProbe = null; it() }
            return result
        }
        override suspend fun frame() = VisualFrame(frameTarget, frameTime, byteArrayOf(1))
        override suspend fun dispatch(target: VisualTarget, input: VisualInput): VisualDispatch {
            if (target != current) return VisualDispatch.NotSent
            dispatched++
            duringDispatch?.invoke()
            return outcome
        }
        override suspend fun listApps() = emptyList<Pair<String, String>>()
        override fun nowNanos() = now
    }

    @Test fun accessOwnerBecomesUnknownWhenWindowChanges() = runTest {
        val backend = Backend()
        val screen = RootVisualScreen(backend, 100, 200)
        assertEquals(listOf<String?>(null), screen.accessPackages())
        screen.openApp("example.app")
        assertEquals(listOf<String?>("example.app"), screen.accessPackages())
        backend.current = backend.current.copy(windowToken = "other-window")
        assertEquals(listOf<String?>(null), screen.accessPackages())
    }

    @Test fun frameProducedDuringInputIsNotServed() = runTest {
        val backend = Backend()
        val screen = RootVisualScreen(backend, 100, 200)
        screen.openApp("example.app")
        backend.now = 105
        backend.duringDispatch = { backend.frameTime = 106; backend.now = 107 }
        assertTrue(screen.tapXY(2, 3) is ActionResult.Ok)
        assertNull(screen.screenshotPng())
        backend.frameTime = 108
        assertNotNull(screen.screenshotPng())
    }

    @Test fun staleFramesAreNotServedAfterLaunchOrInput() = runTest {
        val backend = Backend()
        val screen = RootVisualScreen(backend, 100, 200)
        assertTrue(screen.openApp("example.app") is ActionResult.Ok)
        backend.frameTime = 100
        assertNull(screen.screenshotPng())
        backend.frameTime = 101
        assertNotNull(screen.screenshotPng())
        backend.now = 105
        assertTrue(screen.tapXY(2, 3) is ActionResult.Ok)
        assertNull(screen.screenshotPng())
        backend.frameTime = 106
        assertNotNull(screen.screenshotPng())
    }

    @Test fun changedWindowOrDisplayRefusesCaptureAndInput() = runTest {
        val backend = Backend()
        val screen = RootVisualScreen(backend, 100, 200)
        screen.openApp("example.app")
        backend.current = backend.current.copy(windowToken = "window-2")
        assertNull(screen.screenshotPng())
        assertEquals(ActionResult.Failed("target_unavailable"), screen.tapXY(2, 3))
        assertEquals(0, backend.dispatched)
    }

    @Test fun backendMustRejectWindowSwitchBetweenProbeAndDispatch() = runTest {
        val backend = Backend()
        val screen = RootVisualScreen(backend, 100, 200)
        screen.openApp("example.app")
        backend.afterProbe = { backend.current = backend.current.copy(windowToken = "window-2") }
        assertEquals(ActionResult.Failed("input_not_sent"), screen.tapXY(2, 3))
        assertEquals(0, backend.dispatched)
    }

    @Test fun uncertainInputDoesNotPretendSuccessOrReuseOldFrame() = runTest {
        val backend = Backend()
        val screen = RootVisualScreen(backend, 100, 200)
        screen.openApp("example.app")
        backend.now = 105
        backend.outcome = VisualDispatch.Uncertain
        assertEquals(ActionResult.Failed("input_unconfirmed"), screen.tapXY(2, 3))
        assertNull(screen.screenshotPng())
    }

    @Test fun physicalDisplayAndOutOfBoundsAreRejected() = runTest {
        val backend = Backend()
        backend.current = backend.current.copy(displayId = 0)
        val screen = RootVisualScreen(backend, 100, 200)
        assertEquals(ActionResult.Failed("target_unavailable"), screen.openApp("example.app"))
        assertEquals(ActionResult.Failed("invalid_coordinates"), screen.tapXY(100, 0))
        assertEquals(0, backend.dispatched)
    }
}
