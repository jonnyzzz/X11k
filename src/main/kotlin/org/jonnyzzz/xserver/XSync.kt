package org.jonnyzzz.xserver

internal object XSync {
    const val MajorOpcode = 140
    const val FirstEvent = 72
    const val FirstError = 174
    const val BadCounter = FirstError
    const val BadAlarm = FirstError + 1
    const val BadFence = FirstError + 2
    const val MajorVersion = 3
    const val MinorVersion = 1

    const val Initialize = 0
    const val ListSystemCounters = 1
    const val CreateCounter = 2
    const val SetCounter = 3
    const val ChangeCounter = 4
    const val QueryCounter = 5
    const val DestroyCounter = 6
    const val Await = 7
    const val CreateAlarm = 8
    const val ChangeAlarm = 9
    const val QueryAlarm = 10
    const val DestroyAlarm = 11
    const val SetPriority = 12
    const val GetPriority = 13
    const val CreateFence = 14
    const val TriggerFence = 15
    const val ResetFence = 16
    const val DestroyFence = 17
    const val QueryFence = 18
    const val AwaitFence = 19

    const val ServerTimeCounter = 0x0000_0030
    const val ServerTimeName = "SERVERTIME"

    const val CACounter = 1 shl 0
    const val CAValueType = 1 shl 1
    const val CAValue = 1 shl 2
    const val CATestType = 1 shl 3
    const val CADelta = 1 shl 4
    const val CAEvents = 1 shl 5
    const val AlarmAttributeMask = CACounter or CAValueType or CAValue or CATestType or CADelta or CAEvents

    const val Absolute = 0
    const val Relative = 1

    const val PositiveTransition = 0
    const val NegativeTransition = 1
    const val PositiveComparison = 2
    const val NegativeComparison = 3

    const val AlarmActive = 0
    const val AlarmInactive = 1
    const val AlarmDestroyed = 2

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            Initialize -> "Initialize"
            ListSystemCounters -> "ListSystemCounters"
            CreateCounter -> "CreateCounter"
            SetCounter -> "SetCounter"
            ChangeCounter -> "ChangeCounter"
            QueryCounter -> "QueryCounter"
            DestroyCounter -> "DestroyCounter"
            Await -> "Await"
            CreateAlarm -> "CreateAlarm"
            ChangeAlarm -> "ChangeAlarm"
            QueryAlarm -> "QueryAlarm"
            DestroyAlarm -> "DestroyAlarm"
            SetPriority -> "SetPriority"
            GetPriority -> "GetPriority"
            CreateFence -> "CreateFence"
            TriggerFence -> "TriggerFence"
            ResetFence -> "ResetFence"
            DestroyFence -> "DestroyFence"
            QueryFence -> "QueryFence"
            AwaitFence -> "AwaitFence"
            else -> "Unknown"
        }
}
