package com.wangzan101.mika.cap.phone.shell

/** An independent power group alone does not confine later Activity/Task launches. */
internal class RootDisplayRoutingUnavailable(cause: Throwable? = null) : IllegalStateException(
    "System Activity/Task routing hooks are unavailable; virtual sessions cannot start safely", cause,
)

/** Fail closed on a dead/reused display or loss of the independent power group. */
internal object RootDisplayIsolation {
    const val CREATE_ROUTING_UNAVAILABLE = -2

    /** Do not register a group-0 display with Mirage just to obtain a power group.
     * Its mode-4 task-reuse policy can send source-less launches back to display 0.
     */
    fun requireNativeGroup(id: Int, group: Int?): Int {
        check(id > 0 && group != null && group >= 0) { "display group unavailable: display=$id group=$group" }
        if (group == 0) throw RootDisplayRoutingUnavailable()
        return group
    }

    fun live(id: Int, expected: String?, actual: String?, group: Int?): Boolean =
        id > 0 && !expected.isNullOrBlank() && expected == actual && group != null && group > 0

    /** Removing a ColorOS stream can promote its tasks to display 0. Close them first. */
    fun closeBeforeRelease(tasks: () -> List<Int>, remove: (Int) -> Boolean): Boolean {
        val ids = tasks()
        if (ids.any { !remove(it) }) return false
        return tasks().isEmpty()
    }

    /** removeTask returns before the app acknowledges destroy; the system CLOSE refuses while a
     * DESTROYING ActivityRecord still exists. Retry until it is gone instead of exiting with the
     * lease open, which would make the owner's death keep the package's deny binding.
     */
    fun closeWhenDestroyed(
        close: () -> Unit,
        timeoutMs: Long = 5_000,
        stepMs: Long = 100,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Boolean {
        val deadline = now() + timeoutMs
        while (true) {
            if (runCatching(close).isSuccess) return true
            if (now() >= deadline) return false
            sleep(stepMs)
        }
    }
}
