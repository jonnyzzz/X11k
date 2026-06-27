package org.jonnyzzz.xserver

internal object XShm {
    const val MajorOpcode = 131
    const val FirstEvent = 65
    const val FirstError = 168
    const val MajorVersion = 1
    const val MinorVersion = 2
    const val ZPixmap = 2

    const val QueryVersion = 0
    const val Attach = 1
    const val Detach = 2
    const val PutImage = 3
    const val GetImage = 4
    const val CreatePixmap = 5
    const val AttachFd = 6
    const val CreateSegment = 7
    const val BadSeg = FirstError

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            Attach -> "Attach"
            Detach -> "Detach"
            PutImage -> "PutImage"
            GetImage -> "GetImage"
            CreatePixmap -> "CreatePixmap"
            AttachFd -> "AttachFd"
            CreateSegment -> "CreateSegment"
            else -> "Unknown"
        }
}
