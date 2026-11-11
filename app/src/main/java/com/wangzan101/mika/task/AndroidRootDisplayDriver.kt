package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.uistate.*
import com.wangzan101.mika.shell.IMikaShell
import java.util.UUID

internal interface RootDisplayDevice {
    val displayId: Int
    fun frame(): Pair<Long, ByteArray>?
    fun topTaskId(displayId: Int): Int
    fun displayOfTask(taskId: Int): Int
    fun packageOfTask(taskId: Int): String?
    fun tap(target: RootDisplayTarget, x: Int, y: Int): Int
    fun swipe(target: RootDisplayTarget, input: VisualInput.Swipe): Int
    fun key(target: RootDisplayTarget, keyCode: Int): Int
}

private class AndroidDisplayDevice(private val shell: IMikaShell, private val display: AgentDisplay) : RootDisplayDevice {
    override val displayId get() = display.displayId
    override fun frame() = display.latestTimedFramePng()
    override fun topTaskId(displayId: Int) = shell.topTaskId(displayId)
    override fun displayOfTask(taskId: Int) = shell.displayOfTask(taskId)
    override fun packageOfTask(taskId: Int) = shell.packageOfTask(taskId)
    override fun tap(target: RootDisplayTarget, x: Int, y: Int) = shell.rootTap(target.displayId, target.taskId, target.packageName, x, y)
    override fun swipe(target: RootDisplayTarget, input: VisualInput.Swipe) = shell.rootSwipe(target.displayId, target.taskId, target.packageName, input.x1, input.y1, input.x2, input.y2, input.durationMs.toInt())
    override fun key(target: RootDisplayTarget, keyCode: Int) = shell.rootKey(target.displayId, target.taskId, target.packageName, keyCode)
}

/** Display-wide capture and UID-targeted input; no window-level isolation is implied. */
internal class AndroidRootDisplayDriver(
    private val device: RootDisplayDevice,
) : RootDisplayDriver {
    constructor(shell: IMikaShell, display: AgentDisplay) : this(AndroidDisplayDevice(shell, display))
    private val session = UUID.randomUUID().toString()
    override suspend fun target(): RootDisplayTarget? = runCatching {
        val id = device.displayId
        if (id <= 0) return null
        val task = device.topTaskId(id)
        if (task < 0 || device.displayOfTask(task) != id) return null
        val pkg = device.packageOfTask(task)?.takeIf { it.isNotBlank() } ?: return null
        RootDisplayTarget(id, session, task, pkg)
    }.getOrNull()
    override suspend fun capture(displayId: Int): Pair<Long, ByteArray>? =
        if (displayId == device.displayId && displayId > 0) device.frame() else null
    override suspend fun dispatch(target: RootDisplayTarget, input: VisualInput): VisualDispatch {
        if (target != target()) return VisualDispatch.NotSent
        return try {
            val result = when (input) {
                is VisualInput.Tap -> device.tap(target, input.x, input.y)
                is VisualInput.Swipe -> {
                    if (input.durationMs !in 1..10000) return VisualDispatch.NotSent
                    device.swipe(target, input)
                }
                is VisualInput.Key -> device.key(target, input.keyCode)
            }
            when (result) { 0 -> VisualDispatch.NotSent; 1 -> VisualDispatch.Sent; else -> VisualDispatch.Uncertain }
        } catch (_: Exception) { VisualDispatch.Uncertain }
    }
    override fun nowNanos() = System.nanoTime()
}
