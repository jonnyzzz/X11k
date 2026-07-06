package org.jonnyzzz.xserver

internal object XDoubleBuffer {
    const val MajorOpcode = 142
    const val FirstEvent = 0
    const val FirstError = 182

    const val BadBuffer = FirstError

    const val MajorVersion = 1
    const val MinorVersion = 0

    const val GetVersion = 0
    const val AllocateBackBufferName = 1
    const val DeallocateBackBufferName = 2
    const val SwapBuffers = 3
    const val BeginIdiom = 4
    const val EndIdiom = 5
    const val GetVisualInfo = 6
    const val GetBackBufferAttributes = 7

    const val SwapUndefined = 0
    const val SwapBackground = 1
    const val SwapUntouched = 2
    const val SwapCopied = 3

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            GetVersion -> "GetVersion"
            AllocateBackBufferName -> "AllocateBackBufferName"
            DeallocateBackBufferName -> "DeallocateBackBufferName"
            SwapBuffers -> "SwapBuffers"
            BeginIdiom -> "BeginIdiom"
            EndIdiom -> "EndIdiom"
            GetVisualInfo -> "GetVisualInfo"
            GetBackBufferAttributes -> "GetBackBufferAttributes"
            else -> "Unknown"
        }
}
