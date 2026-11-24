package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.*
import org.junit.Test

class RootDisplayIsolationTest {
    @Test fun defaultGroupIsRejectedBeforeMirageRegistrationOrAnyAppLaunch() {
        try {
            RootDisplayIsolation.requireNativeGroup(14, 0)
            fail("Mirage must not be used as an independent-group fallback")
        } catch (_: RootDisplayRoutingUnavailable) { }
        assertEquals(2, RootDisplayIsolation.requireNativeGroup(14, 2))
    }

    @Test fun missingGroupAndPrimaryDisplayRemainUnavailable() {
        for ((id, group) in listOf(0 to 2, 14 to null, 14 to -1)) {
            try {
                RootDisplayIsolation.requireNativeGroup(id, group)
                fail("invalid display=$id group=$group")
            } catch (_: IllegalStateException) { }
        }
    }

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
