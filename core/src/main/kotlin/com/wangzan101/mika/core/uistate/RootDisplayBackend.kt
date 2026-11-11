package com.wangzan101.mika.core.uistate

data class RootDisplayTarget(val displayId: Int, val session: String, val taskId: Int, val packageName: String)
data class RootDisplayFrame(val target: RootDisplayTarget, val timestampNanos: Long, val png: ByteArray)

/** Implementations must establish root ownership, trusted display grouping and a common monotonic clock. */
interface RootDisplayDriver {
    suspend fun target(): RootDisplayTarget?
    suspend fun capture(displayId: Int): Pair<Long, ByteArray>?
    suspend fun dispatch(target: RootDisplayTarget, input: VisualInput): VisualDispatch
    fun nowNanos(): Long
}

/** Display identity is not a window identity; other windows may obscure or receive input. */
class RootDisplayBackend(private val driver: RootDisplayDriver) {
    private var newerThan = 0L
    private fun valid(t: RootDisplayTarget?) = t != null && t.displayId > 0 && t.session.isNotBlank() && t.taskId >= 0 && t.packageName.isNotBlank()
    suspend fun frame(expected: RootDisplayTarget): RootDisplayFrame? {
        if (!valid(expected) || driver.target() != expected) return null
        val (timestamp, png) = driver.capture(expected.displayId) ?: return null
        if (timestamp <= newerThan || timestamp <= 0 || timestamp > driver.nowNanos() || png.isEmpty()) return null
        if (driver.target() != expected) return null
        return RootDisplayFrame(expected, timestamp, png)
    }
    suspend fun dispatch(expected: RootDisplayTarget, input: VisualInput): VisualDispatch {
        if (!valid(expected) || driver.target() != expected) return VisualDispatch.NotSent
        val result = driver.dispatch(expected, input)
        if (result != VisualDispatch.NotSent) newerThan = driver.nowNanos()
        return result
    }
    fun invalidateFrames() { newerThan = driver.nowNanos() }
}
