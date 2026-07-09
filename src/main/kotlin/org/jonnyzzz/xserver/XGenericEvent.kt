package org.jonnyzzz.xserver

internal object XGenericEvent {
    const val MajorOpcode = 144
    const val FirstEvent = 0
    const val FirstError = 0
    const val MajorVersion = 1
    const val MinorVersion = 0

    const val QueryVersion = 0

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            else -> "Unknown"
        }
}
