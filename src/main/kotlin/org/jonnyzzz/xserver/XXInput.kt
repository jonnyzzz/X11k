package org.jonnyzzz.xserver

internal object XXInput {
    const val MajorOpcode = 143
    const val FirstEvent = 76
    const val FirstError = 183
    const val BadDevice = FirstError

    const val MajorVersion = 2
    const val MinorVersion = 4

    const val MasterPointerId = 2
    const val MasterKeyboardId = 3

    const val XIAllDevices = 0
    const val XIAllMasterDevices = 1

    const val XIDeviceChanged = 1
    const val XIKeyPress = 2
    const val XIKeyRelease = 3
    const val XIMotion = 6
    const val XIHierarchyChanged = 11
    const val XIRawKeyPress = 13
    const val XIRawKeyRelease = 14
    const val XIRawButtonPress = 15
    const val XIRawButtonRelease = 16
    const val XIRawMotion = 17
    const val XITouchBegin = 18
    const val XITouchUpdate = 19
    const val XITouchEnd = 20
    const val XITouchOwnership = 21
    const val XIRawTouchBegin = 22
    const val XIRawTouchUpdate = 23
    const val XIRawTouchEnd = 24
    const val XIGesturePinchBegin = 27
    const val XIGesturePinchUpdate = 28
    const val XIGesturePinchEnd = 29
    const val XIGestureSwipeBegin = 30
    const val XIGestureSwipeUpdate = 31
    const val XIGestureSwipeEnd = 32
    const val XILastEvent = XIGestureSwipeEnd

    val rawEvents = intArrayOf(
        XIRawKeyPress,
        XIRawKeyRelease,
        XIRawButtonPress,
        XIRawButtonRelease,
        XIRawMotion,
        XIRawTouchBegin,
        XIRawTouchUpdate,
        XIRawTouchEnd,
    )
    val touchEvents = intArrayOf(XITouchBegin, XITouchUpdate, XITouchEnd)
    val touchSelectionEvents = touchEvents + XITouchOwnership
    val gesturePinchEvents = intArrayOf(XIGesturePinchBegin, XIGesturePinchUpdate, XIGesturePinchEnd)
    val gestureSwipeEvents = intArrayOf(XIGestureSwipeBegin, XIGestureSwipeUpdate, XIGestureSwipeEnd)
    val exclusiveSelectionEvents = intArrayOf(XITouchBegin, XIGesturePinchBegin, XIGestureSwipeBegin)

    fun eventMaskContains(mask: ByteArray, eventType: Int): Boolean {
        val index = eventType / 8
        return index < mask.size && (mask[index].toInt() and (1 shl (eventType % 8))) != 0
    }

    fun canonicalEventMask(mask: ByteArray): ByteArray {
        val lastNonZero = mask.indexOfLast { it != 0.toByte() }
        if (lastNonZero < 0) return byteArrayOf()
        return mask.copyOf((lastNonZero + 4) and -4)
    }

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
