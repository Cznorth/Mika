package com.wangzan101.mika.cap.phone.shell

/** Fail closed on a dead/reused display or loss of the independent power group. */
internal object RootDisplayIsolation {
    fun live(id: Int, expected: String?, actual: String?, group: Int?): Boolean =
        id > 0 && !expected.isNullOrBlank() && expected == actual && group != null && group > 0

    /** Removing a ColorOS stream can promote its tasks to display 0. Close them first. */
    fun closeBeforeRelease(tasks: () -> List<Int>, remove: (Int) -> Boolean): Boolean {
        val ids = tasks()
        if (ids.any { !remove(it) }) return false
        return tasks().isEmpty()
    }
}
