package com.wangzan101.mika.task

import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidRootDisplayDriverTest {
    private class Device : RootDisplayDevice {
        override var displayId = 4
        var task = 8
        var taskDisplay = 4
        var pkg: String? = "example"
        var result = 1
        var failTarget = false
        var failInput = false
        var released = false
        var calls = 0
        override fun frame(): Pair<Long, ByteArray>? = if (released) null else 90L to byteArrayOf(1)
        override fun topTaskId(displayId: Int): Int { if (failTarget) error("target"); return task }
        override fun displayOfTask(taskId: Int) = taskDisplay
        override fun packageOfTask(taskId: Int) = pkg
        override fun tap(target: RootDisplayTarget, x: Int, y: Int): Int { calls++; if (failInput) error("binder"); return result }
        override fun swipe(target: RootDisplayTarget, input: VisualInput.Swipe) = tap(target, input.x1, input.y1)
        override fun key(target: RootDisplayTarget, keyCode: Int) = tap(target, keyCode, 0)
    }
    @Test fun invalidOrChangedTargetNeverReceivesInput() = runBlocking {
        val d = Device(); val driver = AndroidRootDisplayDriver(d); val target = driver.target()!!
        d.displayId = 0; assertNull(driver.target()); assertEquals(VisualDispatch.NotSent, driver.dispatch(target, VisualInput.Tap(1, 2)))
        d.displayId = 4; d.task = -1; assertNull(driver.target())
        d.task = 8; d.taskDisplay = 5; assertNull(driver.target())
        d.taskDisplay = 4; d.pkg = " "; assertNull(driver.target())
        d.pkg = "example"; d.failTarget = true; assertNull(driver.target())
        assertEquals(0, d.calls)
    }
    @Test fun mapsInputResultsAndRejectsInvalidSwipe() = runBlocking {
        val d = Device(); val driver = AndroidRootDisplayDriver(d); val target = driver.target()!!
        d.result = 0; assertEquals(VisualDispatch.NotSent, driver.dispatch(target, VisualInput.Tap(1, 2)))
        d.result = 1; assertEquals(VisualDispatch.Sent, driver.dispatch(target, VisualInput.Key(3)))
        d.result = 2; assertEquals(VisualDispatch.Uncertain, driver.dispatch(target, VisualInput.Tap(1, 2)))
        d.failInput = true; assertEquals(VisualDispatch.Uncertain, driver.dispatch(target, VisualInput.Tap(1, 2)))
        val before = d.calls
        assertEquals(VisualDispatch.NotSent, driver.dispatch(target, VisualInput.Swipe(0, 0, 1, 1, 0)))
        assertEquals(before, d.calls)
    }
    @Test fun frameIsRestrictedToDisplayAndDisappearsAfterRelease() = runBlocking {
        val d = Device(); val driver = AndroidRootDisplayDriver(d)
        assertNull(driver.capture(0)); assertNull(driver.capture(5)); assertNotNull(driver.capture(4))
        d.released = true; assertNull(driver.capture(4))
    }
}
