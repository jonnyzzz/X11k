package org.jonnyzzz.xserver

internal object XShm {
    const val MajorOpcode = 131
    const val FirstEvent = 65
    const val FirstError = 168
    const val MajorVersion = 1
    const val MinorVersion = 2
    const val ZPixmap = 2

    const val QueryVersion = 0

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            1 -> "Attach"
            2 -> "Detach"
            3 -> "PutImage"
            4 -> "GetImage"
            5 -> "CreatePixmap"
            6 -> "AttachFd"
            7 -> "CreateSegment"
            else -> "Unknown"
        }
}
