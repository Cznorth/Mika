package com.wangzan101.mika.core.uistate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VisualTarget(val displayId: Int, val taskId: Int, val packageName: String, val windowToken: String)
data class VisualFrame(val target: VisualTarget, val timestampNanos: Long, val png: ByteArray)

sealed interface VisualInput {
    data class Tap(val x: Int, val y: Int) : VisualInput
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long) : VisualInput
    data class Key(val keyCode: Int) : VisualInput
}

enum class VisualDispatch { Sent, NotSent, Uncertain }

interface RootVisualBackend {
    suspend fun launch(packageName: String): Boolean
    suspend fun target(): VisualTarget?
    suspend fun frame(): VisualFrame?
    suspend fun dispatch(target: VisualTarget, input: VisualInput): VisualDispatch
    suspend fun listApps(): List<Pair<String, String>>
    fun nowNanos(): Long
}

class RootVisualScreen(
    private val backend: RootVisualBackend,
    private val width: Int,
    private val height: Int,
) : ScreenPort {
    private val mutex = Mutex()
    private var target: VisualTarget? = null
    private var newerThan = 0L
    private var snapshotNumber = 0L

    private fun valid(value: VisualTarget?) = value != null && value.displayId > 0 && value.taskId >= 0 &&
        value.packageName.isNotBlank() && value.windowToken.isNotBlank()

    private suspend fun current(): VisualTarget? {
        val expected = target ?: return null
        val observed = backend.target()
        return expected.takeIf { valid(it) && observed == it }
    }

    /** Null ownership must remain unknown to the application-access guard. */
    suspend fun accessPackages(): List<String?> = mutex.withLock {
        listOf(current()?.packageName)
    }

    private suspend fun checkedFrame(): VisualFrame? {
        val expected = current() ?: return null
        val frame = backend.frame() ?: return null
        if (frame.target != expected || frame.timestampNanos <= newerThan || frame.png.isEmpty()) return null
        return frame.takeIf { current() == expected }
    }

    override suspend fun snapshot(): ScreenSnapshot = mutex.withLock {
        val expected = current()
        val frame = if (expected == null) null else checkedFrame()
        ScreenSnapshot(
            snapshotId = "visual-${++snapshotNumber}", packageName = expected?.packageName,
            elements = emptyList(), width = width, height = height,
            hasWindow = expected != null, unreadableApp = expected?.packageName,
            captureError = if (expected != null && frame == null) "display_capture_unavailable" else null,
        )
    }

    override suspend fun screenshotPng(): ByteArray? = mutex.withLock { checkedFrame()?.png }

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = mutex.withLock {
        if (cold || packageName.isBlank()) return@withLock ActionResult.Failed("unsupported")
        target = null
        newerThan = backend.nowNanos()
        if (!backend.launch(packageName)) return@withLock ActionResult.Failed("app_launch_failed")
        val found = backend.target()
        if (!valid(found) || found?.packageName != packageName) return@withLock ActionResult.Failed("target_unavailable")
        target = found
        ActionResult.Ok()
    }

    private suspend fun send(input: VisualInput): ActionResult = mutex.withLock {
        val expected = current() ?: return@withLock ActionResult.Failed("target_unavailable")
        when (backend.dispatch(expected, input)) {
            VisualDispatch.Sent -> { newerThan = backend.nowNanos(); ActionResult.Ok() }
            VisualDispatch.NotSent -> ActionResult.Failed("input_not_sent")
            VisualDispatch.Uncertain -> { newerThan = backend.nowNanos(); ActionResult.Failed("input_unconfirmed") }
        }
    }

    private fun point(x: Int, y: Int) = x in 0 until width && y in 0 until height
    override suspend fun tapXY(x: Int, y: Int): ActionResult =
        if (point(x, y)) send(VisualInput.Tap(x, y)) else ActionResult.Failed("invalid_coordinates")
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
        if (point(x1, y1) && point(x2, y2) && durationMs in 1..10000)
            send(VisualInput.Swipe(x1, y1, x2, y2, durationMs)) else ActionResult.Failed("invalid_gesture")
    override suspend fun back(): ActionResult = pressKey(4)
    override suspend fun pressKey(keyCode: Int): ActionResult =
        if (keyCode in setOf(4, 66, 67)) send(VisualInput.Key(keyCode)) else ActionResult.Failed("unsupported")
    override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("unsupported")
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("unsupported")
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("unsupported")
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("unsupported")
    override suspend fun waitStable(timeoutMs: Long) = false
    override suspend fun listApps() = backend.listApps()
}
