package org.jonnyzzz.xserver

internal object XXMitMisc {
    const val MajorOpcode = 138
    const val FirstEvent = 0
    const val FirstError = 0

    const val SetBugMode = 0
    const val GetBugMode = 1

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            SetBugMode -> "SetBugMode"
            GetBugMode -> "GetBugMode"
            else -> "Unknown"
        }
}
