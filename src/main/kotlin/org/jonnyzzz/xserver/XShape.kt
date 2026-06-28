package org.jonnyzzz.xserver

internal object XShape {
    const val MajorOpcode = 134
    const val FirstEvent = 70
    const val FirstError = 0
    const val MajorVersion = 1
    const val MinorVersion = 1

    const val QueryVersion = 0
    const val Rectangles = 1
    const val Mask = 2
    const val Combine = 3
    const val Offset = 4
    const val QueryExtents = 5
    const val SelectInput = 6
    const val InputSelected = 7
    const val GetRectangles = 8

    const val OpSet = 0
    const val OpUnion = 1
    const val OpIntersect = 2
    const val OpSubtract = 3
    const val OpInvert = 4

    const val OrderingUnsorted = 0
    const val OrderingYSorted = 1
    const val OrderingYXSorted = 2
    const val OrderingYXBanded = 3

    const val Notify = 0

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            Rectangles -> "Rectangles"
            Mask -> "Mask"
            Combine -> "Combine"
            Offset -> "Offset"
            QueryExtents -> "QueryExtents"
            SelectInput -> "SelectInput"
            InputSelected -> "InputSelected"
            GetRectangles -> "GetRectangles"
            else -> "Unknown"
        }
}
