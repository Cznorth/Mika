package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.*
import org.junit.Test

class RootDisplayIsolationTest {
    @Test fun expiredReusedAndCollapsedDisplaysFailClosed() {
        assertTrue(RootDisplayIsolation.live(7, "virtual:ours,1", "virtual:ours,1", 2))
        assertFalse(RootDisplayIsolation.live(0, "local:main", "local:main", 1))
        assertFalse(RootDisplayIsolation.live(7, "virtual:ours,1", null, 2))
        assertFalse(RootDisplayIsolation.live(7, "virtual:ours,1", "virtual:other,2", 2))
        assertFalse(RootDisplayIsolation.live(7, "virtual:ours,1", "virtual:ours,1", 0))
        assertFalse(RootDisplayIsolation.live(7, "virtual:ours,1", "virtual:ours,1", null))
    }

    @Test fun failedOrAsynchronousTaskRemovalMustNotReleaseTheDisplay() {
        val tasks = mutableListOf(31, 32)
        assertFalse(RootDisplayIsolation.closeBeforeRelease({ tasks.toList() }) { false })
        assertFalse(RootDisplayIsolation.closeBeforeRelease({ tasks.toList() }) { true })
        assertTrue(RootDisplayIsolation.closeBeforeRelease({ tasks.toList() }) { tasks.remove(it) })
        assertTrue(tasks.isEmpty())
    }

    @Test fun taskArrivingDuringCleanupKeepsTheDisplayQuarantined() {
        var reads = 0
        assertFalse(RootDisplayIsolation.closeBeforeRelease(
            tasks = { if (reads++ == 0) listOf(31) else listOf(32) },
            remove = { true },
        ))
    }
}
