package org.jonnyzzz.xserver

internal enum class ByteOrder {
    LsbFirst,
    MsbFirst;

    fun u16(bytes: ByteArray, offset: Int): Int =
        when (this) {
            LsbFirst -> (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            MsbFirst -> ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        }

    fun i16(bytes: ByteArray, offset: Int): Int {
        val value = u16(bytes, offset)
        return if ((value and 0x8000) == 0) value else value - 0x10000
    }

    fun u32(bytes: ByteArray, offset: Int): Int =
        when (this) {
            LsbFirst -> (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

            MsbFirst -> ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        }

    fun valueListU8(bytes: ByteArray, offset: Int): Int =
        when (this) {
            LsbFirst -> bytes[offset].toInt() and 0xff
            MsbFirst -> bytes[offset + 3].toInt() and 0xff
        }

    fun put16(bytes: ByteArray, offset: Int, value: Int) {
        when (this) {
            LsbFirst -> {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
            }
            MsbFirst -> {
                bytes[offset] = (value ushr 8).toByte()
                bytes[offset + 1] = value.toByte()
            }
        }
    }

    fun put32(bytes: ByteArray, offset: Int, value: Int) {
        when (this) {
            LsbFirst -> {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
                bytes[offset + 2] = (value ushr 16).toByte()
                bytes[offset + 3] = (value ushr 24).toByte()
            }
            MsbFirst -> {
                bytes[offset] = (value ushr 24).toByte()
                bytes[offset + 1] = (value ushr 16).toByte()
                bytes[offset + 2] = (value ushr 8).toByte()
                bytes[offset + 3] = value.toByte()
            }
        }
    }

    companion object {
        fun fromSetupByte(value: Int): ByteOrder =
            when (value) {
                0x6c -> LsbFirst
                0x42 -> MsbFirst
                else -> throw IllegalArgumentException("Unsupported X11 byte order byte: 0x${value.toString(16)}")
            }
    }
}

internal object SetupReply {
    private const val ReleaseNumber = 1
    private const val MotionBufferSize = 256
    private const val RootWindowId = X11Ids.RootWindow
    private const val DefaultColormapId = X11Ids.DefaultColormap
    private const val WhitePixel = 0x00ff_ffff
    private const val BlackPixel = 0x0000_0000
    private const val RootVisualId = X11Ids.RootVisual
    private const val RootDepth = X11Ids.RootDepth

    fun success(
        byteOrder: ByteOrder,
        clientMajor: Int,
        clientMinor: Int,
        width: Int,
        height: Int,
        widthMillimeters: Int,
        heightMillimeters: Int,
        currentInputMasks: Int,
        resourceIdBase: Int = X11Ids.ResourceIdBase,
        resourceIdMask: Int = X11Ids.ResourceIdMask,
    ): ByteArray {
        val vendor = "jonnyzzz/x".encodeToByteArray()
        val vendorPaddedLength = paddedLength(vendor.size)
        val pixmapFormatsLength = XPixmapFormats.All.size
        val screensLength = 1
        val depthsLength = XPixmapFormats.All.size
        val visuals = X11Ids.VisualDescriptions
        val depthBytes = XPixmapFormats.All.sumOf { format -> 8 + visuals.count { it.depth == format.depth } * 24 }
        val screenBytes = 40 + depthBytes
        val additionalLength = 32 + vendorPaddedLength + pixmapFormatsLength * 8 + screenBytes
        val reply = ByteArray(8 + additionalLength)

        reply[0] = 1
        byteOrder.put16(reply, 2, clientMajor.coerceAtLeast(11))
        byteOrder.put16(reply, 4, clientMinor)
        byteOrder.put16(reply, 6, additionalLength / 4)
        byteOrder.put32(reply, 8, ReleaseNumber)
        byteOrder.put32(reply, 12, resourceIdBase)
        byteOrder.put32(reply, 16, resourceIdMask)
        byteOrder.put32(reply, 20, MotionBufferSize)
        byteOrder.put16(reply, 24, vendor.size)
        byteOrder.put16(reply, 26, 0xffff)
        reply[28] = screensLength.toByte()
        reply[29] = pixmapFormatsLength.toByte()
        reply[30] = 0
        reply[31] = 0
        reply[32] = 32
        reply[33] = 32
        reply[34] = 8
        reply[35] = 255.toByte()
        vendor.copyInto(reply, 40)

        var offset = 40 + vendorPaddedLength
        for (format in XPixmapFormats.All) {
            reply[offset] = format.depth.toByte()
            reply[offset + 1] = format.bitsPerPixel.toByte()
            reply[offset + 2] = format.scanlinePad.toByte()
            offset += 8
        }

        byteOrder.put32(reply, offset, RootWindowId)
        byteOrder.put32(reply, offset + 4, DefaultColormapId)
        byteOrder.put32(reply, offset + 8, WhitePixel)
        byteOrder.put32(reply, offset + 12, BlackPixel)
        byteOrder.put32(reply, offset + 16, currentInputMasks)
        byteOrder.put16(reply, offset + 20, width)
        byteOrder.put16(reply, offset + 22, height)
        byteOrder.put16(reply, offset + 24, widthMillimeters)
        byteOrder.put16(reply, offset + 26, heightMillimeters)
        byteOrder.put16(reply, offset + 28, 1)
        byteOrder.put16(reply, offset + 30, 1)
        byteOrder.put32(reply, offset + 32, RootVisualId)
        reply[offset + 36] = XBackingStore.WhenMapped.toByte()
        reply[offset + 37] = 0
        reply[offset + 38] = 24
        reply[offset + 39] = depthsLength.toByte()
        offset += 40

        for (format in XPixmapFormats.All) {
            reply[offset] = format.depth.toByte()
            val formatVisuals = visuals.filter { it.depth == format.depth }
            val formatVisualsLength = formatVisuals.size
            byteOrder.put16(reply, offset + 2, formatVisualsLength)
            offset += 8
            for (visual in formatVisuals) {
                byteOrder.put32(reply, offset, visual.id)
                reply[offset + 4] = visual.visualClass.toByte()
                reply[offset + 5] = 8
                byteOrder.put16(reply, offset + 6, 256)
                byteOrder.put32(reply, offset + 8, visual.redMask)
                byteOrder.put32(reply, offset + 12, visual.greenMask)
                byteOrder.put32(reply, offset + 16, visual.blueMask)
                offset += 24
            }
        }

        return reply
    }

    fun failure(
        byteOrder: ByteOrder,
        clientMajor: Int,
        clientMinor: Int,
        reason: String,
    ): ByteArray {
        val reasonBytes = reason.encodeToByteArray().let { if (it.size <= 255) it else it.copyOf(255) }
        val reasonPaddedLength = paddedLength(reasonBytes.size)
        val reply = ByteArray(8 + reasonPaddedLength)

        reply[0] = 0
        reply[1] = reasonBytes.size.toByte()
        byteOrder.put16(reply, 2, clientMajor)
        byteOrder.put16(reply, 4, clientMinor)
        byteOrder.put16(reply, 6, reasonPaddedLength / 4)
        reasonBytes.copyInto(reply, 8)

        return reply
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4
}

internal data class XVisualDescription(
    val id: Int,
    val visualClass: Int,
    val depth: Int,
    val redMask: Int,
    val greenMask: Int,
    val blueMask: Int,
) {
    companion object {
        const val TrueColor = 4
        const val DirectColor = 5
    }
}

internal data class XPixmapFormatEntry(
    val depth: Int,
    val bitsPerPixel: Int,
    val scanlinePad: Int,
)

internal object XPixmapFormats {
    val All = listOf(
        XPixmapFormatEntry(depth = 1, bitsPerPixel = 1, scanlinePad = 32),
        XPixmapFormatEntry(depth = 4, bitsPerPixel = 8, scanlinePad = 32),
        XPixmapFormatEntry(depth = 8, bitsPerPixel = 8, scanlinePad = 32),
        XPixmapFormatEntry(depth = 16, bitsPerPixel = 16, scanlinePad = 32),
        XPixmapFormatEntry(depth = 24, bitsPerPixel = 32, scanlinePad = 32),
        XPixmapFormatEntry(depth = 32, bitsPerPixel = 32, scanlinePad = 32),
    )
    val SupportedDepths = All.map { it.depth }.toSet()

    fun bitsPerPixel(depth: Int): Int? =
        All.firstOrNull { it.depth == depth }?.bitsPerPixel
}

internal object X11Ids {
    const val ResourceIdBase = 0x0020_0000
    const val ResourceIdMask = 0x001f_ffff
    const val ResourceIdStride = ResourceIdMask + 1
    const val RootWindow = 0x0000_0026
    const val DefaultColormap = 0x0000_0027
    const val RootVisual = 0x0000_0021
    const val RgbaVisual = 0x0000_0029
    const val XvfbLikeRootVisualAlias = 0x0000_01cf
    const val XvfbLikeBgrRootVisualAlias = 0x0000_01e3
    const val XvfbLikeRgbaVisualAlias = 0x0000_0213
    const val XvfbLikeBgrRgbaVisualAlias = 0x0000_021d
    const val RootDepth = 24
    const val RgbaDepth = 32

    val RootRgbVisualAliases: List<Int> =
        listOf(RootVisual) + (0x0000_01bd..XvfbLikeRootVisualAlias).toList()
    val RootBgrVisualAliases: List<Int> =
        (0x0000_01d0..XvfbLikeBgrRootVisualAlias).toList()
    val RootVisualAliases: List<Int> =
        RootRgbVisualAliases + RootBgrVisualAliases
    val RootRgbDirectColorAliases: List<Int> =
        listOf(0x0000_0022) + (0x0000_01e4..0x0000_01f6).toList()
    val RootBgrDirectColorAliases: List<Int> =
        (0x0000_01f7..0x0000_020a).toList()
    val RootDirectColorVisualAliases: List<Int> =
        RootRgbDirectColorAliases + RootBgrDirectColorAliases
    val RgbaRgbVisualAliases: List<Int> =
        listOf(0x0000_0040) + (0x0000_020b..XvfbLikeRgbaVisualAlias).toList()
    val RgbaBgrVisualAliases: List<Int> =
        (0x0000_0214..XvfbLikeBgrRgbaVisualAlias).toList()
    val RgbaVisualAliases: List<Int> =
        RgbaRgbVisualAliases + RgbaBgrVisualAliases
    val LegacyRgbaVisualAliases: List<Int> =
        listOf(RgbaVisual)
    val VisualDescriptions: List<XVisualDescription> =
        RootRgbVisualAliases.map { visual ->
            XVisualDescription(
                id = visual,
                visualClass = XVisualDescription.TrueColor,
                depth = RootDepth,
                redMask = 0x00ff_0000,
                greenMask = 0x0000_ff00,
                blueMask = 0x0000_00ff,
            )
        } + RootBgrVisualAliases.map { visual ->
            XVisualDescription(
                id = visual,
                visualClass = XVisualDescription.TrueColor,
                depth = RootDepth,
                redMask = 0x0000_00ff,
                greenMask = 0x0000_ff00,
                blueMask = 0x00ff_0000,
            )
        } + RootRgbDirectColorAliases.map { visual ->
            XVisualDescription(
                id = visual,
                visualClass = XVisualDescription.DirectColor,
                depth = RootDepth,
                redMask = 0x00ff_0000,
                greenMask = 0x0000_ff00,
                blueMask = 0x0000_00ff,
            )
        } + RootBgrDirectColorAliases.map { visual ->
            XVisualDescription(
                id = visual,
                visualClass = XVisualDescription.DirectColor,
                depth = RootDepth,
                redMask = 0x0000_00ff,
                greenMask = 0x0000_ff00,
                blueMask = 0x00ff_0000,
            )
        } + RgbaRgbVisualAliases.map { visual ->
            XVisualDescription(
                id = visual,
                visualClass = XVisualDescription.TrueColor,
                depth = RgbaDepth,
                redMask = 0x00ff_0000,
                greenMask = 0x0000_ff00,
                blueMask = 0x0000_00ff,
            )
        } + RgbaBgrVisualAliases.map { visual ->
            XVisualDescription(
                id = visual,
                visualClass = XVisualDescription.TrueColor,
                depth = RgbaDepth,
                redMask = 0x0000_00ff,
                greenMask = 0x0000_ff00,
                blueMask = 0x00ff_0000,
            )
        }

    fun visualDepth(visual: Int): Int? =
        when (visual) {
            in RootVisualAliases -> RootDepth
            in RootDirectColorVisualAliases -> RootDepth
            in RgbaVisualAliases -> RgbaDepth
            in LegacyRgbaVisualAliases -> RgbaDepth
            else -> null
        }

    fun isSupportedVisual(visual: Int): Boolean =
        visualDepth(visual) != null
}

internal object XWindowClass {
    const val CopyFromParent = 0
    const val InputOutput = 1
    const val InputOnly = 2
}
