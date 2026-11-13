package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.uistate.*
import com.wangzan101.mika.shell.IMikaShell
import android.view.KeyEvent
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.BuildConfig
import java.util.UUID

private const val PASTE_SETTLE_MS = 600L

internal interface RootDisplayDevice {
    val displayId: Int
    fun frame(): Pair<Long, ByteArray>?
    fun topTaskId(displayId: Int): Int
    fun displayOfTask(taskId: Int): Int
    fun packageOfTask(taskId: Int): String?
    fun tap(target: RootDisplayTarget, x: Int, y: Int): Int
    fun swipe(target: RootDisplayTarget, input: VisualInput.Swipe): Int
    fun key(target: RootDisplayTarget, keyCode: Int): Int
    fun paste(target: RootDisplayTarget, text: String): Int = 0
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
    override fun paste(target: RootDisplayTarget, text: String): Int {
        // 锁屏时系统不让任何 App 读剪贴板(粘不进去):纯 ASCII 改按键逐个打,其余临时切到 Mika 输入法直接提交
        if (shell.setClipText(text) != ShellService.CLIP_SET) {
            if (AsciiTextKeys.supports(text)) return shell.injectAsciiText(target.displayId, target.taskId, target.packageName, text)
            if (shell.topTaskId(target.displayId) != target.taskId) {
                android.util.Log.w("MikaIme", "paste: task ${target.taskId} no longer on top")
                return AsciiTextKeys.NOT_SENT
            }
            return MikaImeService.type(shell, BuildConfig.APPLICATION_ID, target.displayId, target.packageName, text)
        }
        try {
            if (shell.topTaskId(target.displayId) != target.taskId) return AsciiTextKeys.NOT_SENT
            return shell.injectCtrlKey(target.displayId, KeyEvent.KEYCODE_V)
        } finally {
            // 应用读剪贴板是异步的:等它粘完再还原
            Thread.sleep(PASTE_SETTLE_MS)
            runCatching { shell.restoreClip() }
        }
    }
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
                is VisualInput.Paste -> if (input.text.isEmpty()) return VisualDispatch.NotSent else device.paste(target, input.text)
            }
            when (result) { 0 -> VisualDispatch.NotSent; 1 -> VisualDispatch.Sent; else -> VisualDispatch.Uncertain }
        } catch (_: Exception) { VisualDispatch.Uncertain }
    }
    override fun nowNanos() = System.nanoTime()
}
