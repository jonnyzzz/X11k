package org.jonnyzzz.xserver

internal object XXinerama {
    const val MajorOpcode = 135
    const val FirstEvent = 0
    const val FirstError = 0
    const val MajorVersion = 1
    const val MinorVersion = 1

    const val QueryVersion = 0
    const val IsActive = 4
    const val QueryScreens = 5

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            IsActive -> "IsActive"
            QueryScreens -> "QueryScreens"
            else -> "Unknown"
        }
}
