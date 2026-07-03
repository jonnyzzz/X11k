package org.jonnyzzz.xserver

internal object XXkb {
    const val MajorOpcode = 132
    const val FirstEvent = 66
    const val FirstError = 169
    const val MajorVersion = 1
    const val MinorVersion = 0

    const val UseExtension = 0
    const val SelectEvents = 1
    const val Bell = 3
    const val GetState = 4
    const val LatchLockState = 5
    const val GetControls = 6
    const val SetControls = 7
    const val GetMap = 8
    const val SetMap = 9
    const val GetCompatMap = 10
    const val SetCompatMap = 11
    const val GetIndicatorState = 12
    const val GetIndicatorMap = 13
    const val SetIndicatorMap = 14
    const val GetNamedIndicator = 15
    const val SetNamedIndicator = 16
    const val GetNames = 17
    const val SetNames = 18
    const val GetGeometry = 19
    const val SetGeometry = 20
    const val PerClientFlags = 21
    const val ListComponents = 22
    const val GetKbdByName = 23
    const val GetDeviceInfo = 24
    const val SetDeviceInfo = 25
    const val SetDebuggingFlags = 101

    const val MapNotify = 1
    const val StateNotify = 2
    const val ControlsNotify = 3
    const val IndicatorStateNotify = 4
    const val IndicatorMapNotify = 5
    const val NamesNotify = 6
    const val CompatMapNotify = 7
    const val BellNotify = 8

    const val EventNewKeyboardNotify = 1 shl 0
    const val EventMapNotify = 1 shl 1
    const val EventStateNotify = 1 shl 2
    const val EventControlsNotify = 1 shl 3
    const val EventIndicatorStateNotify = 1 shl 4
    const val EventIndicatorMapNotify = 1 shl 5
    const val EventNamesNotify = 1 shl 6
    const val EventCompatMapNotify = 1 shl 7
    const val EventBellNotify = 1 shl 8
    const val EventActionMessage = 1 shl 9
    const val EventAccessXNotify = 1 shl 10
    const val EventExtensionDeviceNotify = 1 shl 11
    const val AllEventsMask =
        EventNewKeyboardNotify or
            EventMapNotify or
            EventStateNotify or
            EventControlsNotify or
            EventIndicatorStateNotify or
            EventIndicatorMapNotify or
            EventNamesNotify or
            EventCompatMapNotify or
            EventBellNotify or
            EventActionMessage or
            EventAccessXNotify or
            EventExtensionDeviceNotify

    const val ModifierStateMask = 1 shl 0
    const val ModifierBaseMask = 1 shl 1
    const val ModifierLatchMask = 1 shl 2
    const val ModifierLockMask = 1 shl 3
    const val GroupStateMask = 1 shl 4
    const val GroupBaseMask = 1 shl 5
    const val GroupLatchMask = 1 shl 6
    const val GroupLockMask = 1 shl 7
    const val CompatStateMask = 1 shl 8
    const val GrabModsMask = 1 shl 9
    const val CompatGrabModsMask = 1 shl 10
    const val LookupModsMask = 1 shl 11
    const val CompatLookupModsMask = 1 shl 12
    const val PointerButtonMask = 1 shl 13
    const val AllStateComponentsMask = 0x3fff

    const val BoolCtrlRepeatKeys = 1 shl 0
    const val ControlEnabledMask = 1 shl 31
    const val AllControlsMask = -0x07ffe001
    const val AllIndicatorEventsMask = -1
    const val AllBellEventsMask = 1 shl 0
    const val AllGroupsMask = 0x0f
    const val CompatMapSymInterpret = 1 shl 0
    const val CompatMapGroupCompat = 1 shl 1
    const val AllCompatMapMask =
        CompatMapSymInterpret or
            CompatMapGroupCompat
    const val MapPartKeyTypes = 1 shl 0
    const val MapPartKeySyms = 1 shl 1
    const val MapPartModifierMap = 1 shl 2
    const val MapPartExplicitComponents = 1 shl 3
    const val MapPartKeyActions = 1 shl 4
    const val MapPartKeyBehaviors = 1 shl 5
    const val MapPartVirtualMods = 1 shl 6
    const val MapPartVirtualModMap = 1 shl 7
    const val AllMapParts =
        MapPartKeyTypes or
            MapPartKeySyms or
            MapPartModifierMap or
            MapPartExplicitComponents or
            MapPartKeyActions or
            MapPartKeyBehaviors or
            MapPartVirtualMods or
            MapPartVirtualModMap
    const val NameDetailKeycodes = 1 shl 0
    const val NameDetailGeometry = 1 shl 1
    const val NameDetailSymbols = 1 shl 2
    const val NameDetailPhysSymbols = 1 shl 3
    const val NameDetailTypes = 1 shl 4
    const val NameDetailCompat = 1 shl 5
    const val NameDetailKeyTypeNames = 1 shl 6
    const val NameDetailKtLevelNames = 1 shl 7
    const val NameDetailIndicatorNames = 1 shl 8
    const val NameDetailKeyNames = 1 shl 9
    const val NameDetailKeyAliases = 1 shl 10
    const val NameDetailVirtualModNames = 1 shl 11
    const val NameDetailGroupNames = 1 shl 12
    const val NameDetailRgNames = 1 shl 13
    const val AllNameEventsMask = 0x3fff
    const val ComponentNameDetails =
        NameDetailKeycodes or
            NameDetailGeometry or
            NameDetailSymbols or
            NameDetailPhysSymbols or
            NameDetailTypes or
            NameDetailCompat
    const val ListComponentDefault = 1 shl 1
    const val XiFeatureButtonActions = 1 shl 1
    const val XiFeatureIndicatorNames = 1 shl 2
    const val XiFeatureIndicatorMaps = 1 shl 3
    const val XiFeatureIndicatorState = 1 shl 4
    const val XiFeatureIndicators = XiFeatureIndicatorNames or XiFeatureIndicatorMaps or XiFeatureIndicatorState
    const val DeviceSpecUseCoreKeyboard = 0x0100
    const val DeviceSpecUseCorePointer = 0x0200
    const val KbdFeedbackClass = 0
    const val LedFeedbackClass = 4
    const val DfltXIClass = 0x0300
    const val DfltXIId = 0x0400
    const val AllXIClasses = 0x0500
    const val AllXIIds = 0x0600
    const val DefaultMouseKeysButton = 1
    const val DefaultGroupCount = 1
    const val DefaultRepeatDelay = 660
    const val DefaultRepeatInterval = 40

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            UseExtension -> "UseExtension"
            SelectEvents -> "SelectEvents"
            Bell -> "Bell"
            GetState -> "GetState"
            LatchLockState -> "LatchLockState"
            GetControls -> "GetControls"
            SetControls -> "SetControls"
            GetMap -> "GetMap"
            SetMap -> "SetMap"
            GetCompatMap -> "GetCompatMap"
            SetCompatMap -> "SetCompatMap"
            GetIndicatorState -> "GetIndicatorState"
            GetIndicatorMap -> "GetIndicatorMap"
            SetIndicatorMap -> "SetIndicatorMap"
            GetNamedIndicator -> "GetNamedIndicator"
            SetNamedIndicator -> "SetNamedIndicator"
            GetNames -> "GetNames"
            SetNames -> "SetNames"
            GetGeometry -> "GetGeometry"
            SetGeometry -> "SetGeometry"
            PerClientFlags -> "PerClientFlags"
            ListComponents -> "ListComponents"
            GetKbdByName -> "GetKbdByName"
            GetDeviceInfo -> "GetDeviceInfo"
            SetDeviceInfo -> "SetDeviceInfo"
            SetDebuggingFlags -> "SetDebuggingFlags"
            else -> "Unknown"
        }
}
