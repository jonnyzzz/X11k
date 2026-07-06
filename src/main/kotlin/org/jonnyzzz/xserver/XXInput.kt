package org.jonnyzzz.xserver

internal object XXInput {
    const val MajorOpcode = 143
    const val FirstEvent = 76
    const val FirstError = 183

    const val MajorVersion = 2
    const val MinorVersion = 0

    const val MasterPointerId = 2
    const val MasterKeyboardId = 3

    const val XIAllDevices = 0
    const val XIAllMasterDevices = 1

    const val XIKeyClass = 0
    const val XIButtonClass = 1
    const val XIMasterPointer = 1
    const val XIMasterKeyboard = 2

    const val IsXPointer = 0
    const val IsXKeyboard = 1
    const val KeyClass = 0
    const val ButtonClass = 1

    const val GetExtensionVersion = 1
    const val ListInputDevices = 2

    const val XIQueryPointer = 40
    const val XIWarpPointer = 41
    const val XIChangeCursor = 42
    const val XIChangeHierarchy = 43
    const val XISetClientPointer = 44
    const val XIGetClientPointer = 45
    const val XISelectEvents = 46
    const val XIQueryVersion = 47
    const val XIQueryDevice = 48
    const val XISetFocus = 49
    const val XIGetFocus = 50
    const val XIGrabDevice = 51
    const val XIUngrabDevice = 52
    const val XIAllowEvents = 53
    const val XIPassiveGrabDevice = 54
    const val XIPassiveUngrabDevice = 55
    const val XIListProperties = 56
    const val XIChangeProperty = 57
    const val XIDeleteProperty = 58
    const val XIGetProperty = 59
    const val XIGetSelectedEvents = 60
    const val XIBarrierReleasePointer = 61

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            GetExtensionVersion -> "GetExtensionVersion"
            ListInputDevices -> "ListInputDevices"
            XIQueryPointer -> "XIQueryPointer"
            XIWarpPointer -> "XIWarpPointer"
            XIChangeCursor -> "XIChangeCursor"
            XIChangeHierarchy -> "XIChangeHierarchy"
            XISetClientPointer -> "XISetClientPointer"
            XIGetClientPointer -> "XIGetClientPointer"
            XISelectEvents -> "XISelectEvents"
            XIQueryVersion -> "XIQueryVersion"
            XIQueryDevice -> "XIQueryDevice"
            XISetFocus -> "XISetFocus"
            XIGetFocus -> "XIGetFocus"
            XIGrabDevice -> "XIGrabDevice"
            XIUngrabDevice -> "XIUngrabDevice"
            XIAllowEvents -> "XIAllowEvents"
            XIPassiveGrabDevice -> "XIPassiveGrabDevice"
            XIPassiveUngrabDevice -> "XIPassiveUngrabDevice"
            XIListProperties -> "XIListProperties"
            XIChangeProperty -> "XIChangeProperty"
            XIDeleteProperty -> "XIDeleteProperty"
            XIGetProperty -> "XIGetProperty"
            XIGetSelectedEvents -> "XIGetSelectedEvents"
            XIBarrierReleasePointer -> "XIBarrierReleasePointer"
            else -> "Unknown$minorOpcode"
        }
}
