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
}
