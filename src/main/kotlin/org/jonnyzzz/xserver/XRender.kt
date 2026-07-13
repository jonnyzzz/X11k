package org.jonnyzzz.xserver

internal object XRender {
    const val MajorOpcode = 130
    const val FirstEvent = 0
    const val FirstError = 160
    const val MajorVersion = 0
    const val MinorVersion = 11
    const val PictFormatError = FirstError
    const val PictureError = FirstError + 1
    const val GlyphSetError = FirstError + 3
    const val GlyphError = FirstError + 4

    const val A1Format = 0x0000_0023
    const val A8Format = 0x0000_0024
    const val Argb32Format = 0x0000_0025
    const val Xrgb32Format = 0x0000_0026
    const val Abgr32Format = 0x0000_0027
    const val Xbgr32Format = 0x0000_0028
    const val Rgb24Format = 0x0000_0029
    const val Bgr24Format = 0x0000_002a
    const val X4R4G4B4Format = 0x0000_002b
    const val X4B4G4R4Format = 0x0000_002c
    const val X1R5G5B5Format = 0x0000_002d
    const val X1B5G5R5Format = 0x0000_002e
    const val A1R5G5B5Format = 0x0000_002f
    const val A1B5G5R5Format = 0x0000_0030
    const val R5G6B5Format = 0x0000_0031
    const val B5G6R5Format = 0x0000_0032
    const val A4R4G4B4Format = 0x0000_0033
    const val A4B4G4R4Format = 0x0000_0034
    const val Bgr32Format = 0x0000_0035
    const val A2R10G10B10Format = 0x0000_0036
    const val X2R10G10B10Format = 0x0000_0037
    const val A2B10G10R10Format = 0x0000_0038
    const val X2B10G10R10Format = 0x0000_0039

    val PictFormatSpecs = listOf(
        PictFormatSpec(A1Format, depth = 1, redShift = 0, redMask = 0, greenShift = 0, greenMask = 0, blueShift = 0, blueMask = 0, alphaShift = 0, alphaMask = 0x1),
        PictFormatSpec(A8Format, depth = 8, redShift = 0, redMask = 0, greenShift = 0, greenMask = 0, blueShift = 0, blueMask = 0, alphaShift = 0, alphaMask = 0xff),
        PictFormatSpec(Argb32Format, depth = 32, redShift = 16, greenShift = 8, blueShift = 0, alphaShift = 24, alphaMask = 0xff),
        PictFormatSpec(Xrgb32Format, depth = 32, redShift = 16, greenShift = 8, blueShift = 0, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(Abgr32Format, depth = 32, redShift = 8, greenShift = 16, blueShift = 24, alphaShift = 0, alphaMask = 0xff),
        PictFormatSpec(Xbgr32Format, depth = 32, redShift = 8, greenShift = 16, blueShift = 24, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(Rgb24Format, depth = 24, redShift = 16, greenShift = 8, blueShift = 0, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(Bgr24Format, depth = 24, redShift = 0, greenShift = 8, blueShift = 16, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(X4R4G4B4Format, depth = 16, redShift = 8, redMask = 0xf, greenShift = 4, greenMask = 0xf, blueShift = 0, blueMask = 0xf, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(X4B4G4R4Format, depth = 16, redShift = 0, redMask = 0xf, greenShift = 4, greenMask = 0xf, blueShift = 8, blueMask = 0xf, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(X1R5G5B5Format, depth = 16, redShift = 10, redMask = 0x1f, greenShift = 5, greenMask = 0x1f, blueShift = 0, blueMask = 0x1f, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(X1B5G5R5Format, depth = 16, redShift = 0, redMask = 0x1f, greenShift = 5, greenMask = 0x1f, blueShift = 10, blueMask = 0x1f, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(A1R5G5B5Format, depth = 16, redShift = 10, redMask = 0x1f, greenShift = 5, greenMask = 0x1f, blueShift = 0, blueMask = 0x1f, alphaShift = 15, alphaMask = 0x1),
        PictFormatSpec(A1B5G5R5Format, depth = 16, redShift = 0, redMask = 0x1f, greenShift = 5, greenMask = 0x1f, blueShift = 10, blueMask = 0x1f, alphaShift = 15, alphaMask = 0x1),
        PictFormatSpec(R5G6B5Format, depth = 16, redShift = 11, redMask = 0x1f, greenShift = 5, greenMask = 0x3f, blueShift = 0, blueMask = 0x1f, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(B5G6R5Format, depth = 16, redShift = 0, redMask = 0x1f, greenShift = 5, greenMask = 0x3f, blueShift = 11, blueMask = 0x1f, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(A4R4G4B4Format, depth = 16, redShift = 8, redMask = 0xf, greenShift = 4, greenMask = 0xf, blueShift = 0, blueMask = 0xf, alphaShift = 12, alphaMask = 0xf),
        PictFormatSpec(A4B4G4R4Format, depth = 16, redShift = 0, redMask = 0xf, greenShift = 4, greenMask = 0xf, blueShift = 8, blueMask = 0xf, alphaShift = 12, alphaMask = 0xf),
        PictFormatSpec(Bgr32Format, depth = 32, redShift = 0, greenShift = 8, blueShift = 16, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(A2R10G10B10Format, depth = 32, redShift = 20, redMask = 0x3ff, greenShift = 10, greenMask = 0x3ff, blueShift = 0, blueMask = 0x3ff, alphaShift = 30, alphaMask = 0x3),
        PictFormatSpec(X2R10G10B10Format, depth = 32, redShift = 20, redMask = 0x3ff, greenShift = 10, greenMask = 0x3ff, blueShift = 0, blueMask = 0x3ff, alphaShift = 0, alphaMask = 0),
        PictFormatSpec(A2B10G10R10Format, depth = 32, redShift = 0, redMask = 0x3ff, greenShift = 10, greenMask = 0x3ff, blueShift = 20, blueMask = 0x3ff, alphaShift = 30, alphaMask = 0x3),
        PictFormatSpec(X2B10G10R10Format, depth = 32, redShift = 0, redMask = 0x3ff, greenShift = 10, greenMask = 0x3ff, blueShift = 20, blueMask = 0x3ff, alphaShift = 0, alphaMask = 0),
    )
    val DirectFormats = PictFormatSpecs.map { it.id }.toSet()
    val PictFormats = DirectFormats

    data class PictFormatSpec(
        val id: Int,
        val depth: Int,
        val redShift: Int,
        val redMask: Int = 0xff,
        val greenShift: Int,
        val greenMask: Int = 0xff,
        val blueShift: Int,
        val blueMask: Int = 0xff,
        val alphaShift: Int,
        val alphaMask: Int,
    )

    const val OpClear = 0
    const val OpSrc = 1
    const val OpDst = 2
    const val OpOver = 3
    const val OpOverReverse = 4
    const val OpIn = 5
    const val OpInReverse = 6
    const val OpOut = 7
    const val OpOutReverse = 8
    const val OpAtop = 9
    const val OpAtopReverse = 10
    const val OpXor = 11
    const val OpAdd = 12
    const val OpSaturate = 13
    const val OpMaximum = 13
    const val OpDisjointClear = 0x10
    const val OpDisjointSrc = 0x11
    const val OpDisjointDst = 0x12
    const val OpDisjointOver = 0x13
    const val OpDisjointOverReverse = 0x14
    const val OpDisjointIn = 0x15
    const val OpDisjointInReverse = 0x16
    const val OpDisjointOut = 0x17
    const val OpDisjointOutReverse = 0x18
    const val OpDisjointAtop = 0x19
    const val OpDisjointAtopReverse = 0x1a
    const val OpDisjointXor = 0x1b
    const val OpDisjointMaximum = 0x1b
    const val OpConjointClear = 0x20
    const val OpConjointSrc = 0x21
    const val OpConjointDst = 0x22
    const val OpConjointOver = 0x23
    const val OpConjointOverReverse = 0x24
    const val OpConjointIn = 0x25
    const val OpConjointInReverse = 0x26
    const val OpConjointOut = 0x27
    const val OpConjointOutReverse = 0x28
    const val OpConjointAtop = 0x29
    const val OpConjointAtopReverse = 0x2a
    const val OpConjointXor = 0x2b
    const val OpConjointMaximum = 0x2b
    const val OpBlendMultiply = 0x30
    const val OpBlendScreen = 0x31
    const val OpBlendOverlay = 0x32
    const val OpBlendDarken = 0x33
    const val OpBlendLighten = 0x34
    const val OpBlendColorDodge = 0x35
    const val OpBlendColorBurn = 0x36
    const val OpBlendHardLight = 0x37
    const val OpBlendSoftLight = 0x38
    const val OpBlendDifference = 0x39
    const val OpBlendExclusion = 0x3a
    const val OpBlendHSLHue = 0x3b
    const val OpBlendHSLSaturation = 0x3c
    const val OpBlendHSLColor = 0x3d
    const val OpBlendHSLLuminosity = 0x3e
    const val OpBlendMaximum = 0x3e

    const val CPRepeat = 1 shl 0
    const val CPAlphaMap = 1 shl 1
    const val CPAlphaXOrigin = 1 shl 2
    const val CPAlphaYOrigin = 1 shl 3
    const val CPClipXOrigin = 1 shl 4
    const val CPClipYOrigin = 1 shl 5
    const val CPClipMask = 1 shl 6
    const val CPGraphicsExposure = 1 shl 7
    const val CPSubwindowMode = 1 shl 8
    const val CPPolyEdge = 1 shl 9
    const val CPPolyMode = 1 shl 10
    const val CPDither = 1 shl 11
    const val CPComponentAlpha = 1 shl 12
    const val PictureAttributeMask = 0x0000_1fff

    const val RepeatNone = 0
    const val RepeatNormal = 1
    const val RepeatPad = 2
    const val RepeatReflect = 3
    const val SubwindowModeClipByChildren = 0
    const val SubwindowModeIncludeInferiors = 1
    const val PolyEdgeSharp = 0
    const val PolyEdgeSmooth = 1
    const val DefaultPolyEdge = PolyEdgeSmooth
    const val PolyModePrecise = 0
    const val PolyModeImprecise = 1
    const val DefaultPolyMode = PolyModePrecise
    const val LegacyTransformFilterNearest = 0
    const val LegacyTransformFilterBilinear = 1
    const val LegacyTransformFilterFast = 2
    const val LegacyTransformFilterGood = 3
    const val LegacyTransformFilterBest = 4
    const val FilterNearest = "nearest"
    const val FilterBilinear = "bilinear"

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            0 -> "QueryVersion"
            1 -> "QueryPictFormats"
            2 -> "QueryPictIndexValues"
            3 -> "QueryDithers"
            4 -> "CreatePicture"
            5 -> "ChangePicture"
            6 -> "SetPictureClipRectangles"
            7 -> "FreePicture"
            8 -> "Composite"
            9 -> "Scale"
            10 -> "Trapezoids"
            11 -> "Triangles"
            12 -> "TriStrip"
            13 -> "TriFan"
            14 -> "ColorTrapezoids"
            15 -> "ColorTriangles"
            16 -> "Transform"
            17 -> "CreateGlyphSet"
            18 -> "ReferenceGlyphSet"
            19 -> "FreeGlyphSet"
            20 -> "AddGlyphs"
            21 -> "AddGlyphsFromPicture"
            22 -> "FreeGlyphs"
            23 -> "CompositeGlyphs8"
            24 -> "CompositeGlyphs16"
            25 -> "CompositeGlyphs32"
            26 -> "FillRectangles"
            27 -> "CreateCursor"
            28 -> "SetPictureTransform"
            29 -> "QueryFilters"
            30 -> "SetPictureFilter"
            31 -> "CreateAnimCursor"
            32 -> "AddTraps"
            33 -> "CreateSolidFill"
            34 -> "CreateLinearGradient"
            35 -> "CreateRadialGradient"
            36 -> "CreateConicalGradient"
            else -> "Unknown"
        }

    fun isAlphaMaskFormat(format: Int): Boolean =
        format == A8Format || format == A1Format

    fun isValidOperator(operation: Int): Boolean =
        operation in OpClear..OpMaximum ||
            operation in OpDisjointClear..OpDisjointMaximum ||
            operation in OpConjointClear..OpConjointMaximum ||
            operation in OpBlendMultiply..OpBlendMaximum

    fun isValidRepeat(repeat: Int): Boolean =
        repeat in RepeatNone..RepeatReflect

    fun isValidBoolValue(value: Int): Boolean =
        value == 0 || value == 1

    fun isValidSubwindowMode(subwindowMode: Int): Boolean =
        subwindowMode == SubwindowModeClipByChildren || subwindowMode == SubwindowModeIncludeInferiors

    fun isValidPolyEdge(polyEdge: Int): Boolean =
        polyEdge == PolyEdgeSharp || polyEdge == PolyEdgeSmooth

    fun isValidPolyMode(polyMode: Int): Boolean =
        polyMode == PolyModePrecise || polyMode == PolyModeImprecise

    fun pictFormatSpec(format: Int): PictFormatSpec? =
        PictFormatSpecs.firstOrNull { it.id == format }

    fun formatDepth(format: Int): Int? =
        pictFormatSpec(format)?.depth

    fun isRgbLikeFormat(format: Int): Boolean =
        isComponentFormat(format) && !hasAlphaComponent(format)

    fun isComponentFormat(format: Int): Boolean =
        pictFormatSpec(format)?.let { it.redMask != 0 || it.greenMask != 0 || it.blueMask != 0 } == true

    fun hasAlphaComponent(format: Int): Boolean =
        pictFormatSpec(format)?.let { it.alphaMask != 0 } == true

    fun directPixelToArgb(pixel: Int, format: Int): Int? {
        val spec = pictFormatSpec(format) ?: return null
        if (!isComponentFormat(format)) return null
        val red = component(pixel, spec.redShift, spec.redMask)
        val green = component(pixel, spec.greenShift, spec.greenMask)
        val blue = component(pixel, spec.blueShift, spec.blueMask)
        val alpha = if (spec.alphaMask == 0) 0xff else component(pixel, spec.alphaShift, spec.alphaMask)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun directFormatStrideBytes(format: Int, width: Int): Int? {
        if (width <= 0) return null
        val bitsPerPixel = when (formatDepth(format)) {
            16 -> 16
            24, 32 -> 32
            else -> return null
        }
        val bytes = width.toLong() * bitsPerPixel / 8L
        return ((bytes + 3L) and -4L).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun bgrToRgb(pixel: Int): Int =
        ((pixel and 0x0000_00ff) shl 16) or
            (pixel and 0x0000_ff00) or
            ((pixel ushr 16) and 0x0000_00ff)

    private fun component(pixel: Int, shift: Int, mask: Int): Int {
        if (mask == 0) return 0
        val value = (pixel ushr shift) and mask
        return if (mask == 0xff) value else (value * 255 + mask / 2) / mask
    }

    fun argb32Pixel(red: Int, green: Int, blue: Int, alpha: Int): Int =
        ((alpha ushr 8).coerceIn(0, 255) shl 24) or
            ((red ushr 8).coerceIn(0, 255) shl 16) or
            ((green ushr 8).coerceIn(0, 255) shl 8) or
            (blue ushr 8).coerceIn(0, 255)

    fun repeatName(repeat: Int): String =
        when (repeat) {
            RepeatNone -> "none"
            RepeatNormal -> "normal"
            RepeatPad -> "pad"
            RepeatReflect -> "reflect"
            else -> "unknown-$repeat"
        }
}
