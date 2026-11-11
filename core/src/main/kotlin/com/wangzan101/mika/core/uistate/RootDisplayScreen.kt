package com.wangzan101.mika.core.uistate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootDisplayScreen(
    private val backend: RootDisplayBackend,
    private val driver: RootDisplayDriver,
    private val launch: suspend (String) -> Boolean,
    private val apps: suspend () -> List<Pair<String, String>>,
    private val width: Int,
    private val height: Int,
) : ScreenPort {
    private val mutex = Mutex()
    private var target: RootDisplayTarget? = null
    private var newerThan = 0L
    private var snapshotNumber = 0L

    private fun valid(value: RootDisplayTarget?) = value != null && value.displayId > 0 && value.taskId >= 0 &&
        value.packageName.isNotBlank() && value.session.isNotBlank()

    private suspend fun current(): RootDisplayTarget? {
        val expected = target ?: return null
        val observed = driver.target()
        return expected.takeIf { valid(it) && observed == it }
    }

    /** Null ownership must remain unknown to the application-access guard. */
    suspend fun accessPackages(): List<String?> = mutex.withLock {
        listOf(current()?.packageName)
    }

    private suspend fun checkedFrame(): RootDisplayFrame? {
        val expected = current() ?: return null
        val frame = backend.frame(expected) ?: return null
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
        newerThan = driver.nowNanos()
        if (!launch(packageName)) return@withLock ActionResult.Failed("app_launch_failed")
        val found = driver.target()
        if (!valid(found) || found?.packageName != packageName) return@withLock ActionResult.Failed("target_unavailable")
        target = found
        ActionResult.Ok()
    }

    private suspend fun send(input: VisualInput): ActionResult = mutex.withLock {
        val expected = current() ?: return@withLock ActionResult.Failed("target_unavailable")
        when (backend.dispatch(expected, input)) {
            VisualDispatch.Sent -> { newerThan = driver.nowNanos(); ActionResult.Ok() }
            VisualDispatch.NotSent -> ActionResult.Failed("input_not_sent")
            VisualDispatch.Uncertain -> { newerThan = driver.nowNanos(); ActionResult.Failed("input_unconfirmed") }
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
    override suspend fun listApps() = apps()
}
