package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootTapTest {
    @Test fun onlyAcceptsCoordinatesInsideSecondaryDisplay() {
        assertEquals("input -d 3 tap 50 99", ShellService.rootTapCommand(3, 50, 99, 100, 100))
        assertNull(ShellService.rootTapCommand(0, 50, 50, 100, 100))
        assertNull(ShellService.rootTapCommand(3, -1, 50, 100, 100))
        assertNull(ShellService.rootTapCommand(3, 100, 50, 100, 100))
        assertNull(ShellService.rootTapCommand(3, 50, 100, 100, 100))
        assertNull(ShellService.rootTapCommand(3, 50, 50, 0, 100))
    }

    @Test fun swipeRequiresValidDisplayBoundsAndDuration() {
        assertEquals("input -d 3 swipe 0 1 99 98 400", ShellService.rootSwipeCommand(3, 0, 1, 99, 98, 400, 100, 100))
        assertNull(ShellService.rootSwipeCommand(0, 0, 1, 99, 98, 400, 100, 100))
        assertNull(ShellService.rootSwipeCommand(3, 0, 1, 100, 98, 400, 100, 100))
        assertNull(ShellService.rootSwipeCommand(3, 0, 1, 99, 98, 0, 100, 100))
        assertNull(ShellService.rootSwipeCommand(3, 0, 1, 99, 98, 10001, 100, 100))
    }

    @Test fun keyCommandsRejectPhysicalScreenAndUnapprovedKeys() {
        assertEquals("input -d 3 keyevent 4", ShellService.rootKeyCommand(3, 4))
        assertNull(ShellService.rootKeyCommand(0, 4))
        assertNull(ShellService.rootKeyCommand(3, 26))
    }
}
