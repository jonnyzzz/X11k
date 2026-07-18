package org.jonnyzzz.xserver

internal object TextScreenRenderer {
    private const val MaxGlxOperationsInTextReport = 200
    private const val MaxRenderOperationsInTextReport = 30
    private const val MaxRegionalRenderOperationsInTextReport = 200
    private const val MaxCoreCopyCommandsInTextReport = 40
    private const val MaxCoreCopyClipRectanglesInTextReport = 24
    private const val TopMappedRootChildBandHeight = 120
    private const val RightMappedRootChildBandWidth = 96
    private const val BottomMappedRootChildBandHeight = 96

    fun html(snapshot: XScreenSnapshot): String =
        XmlDom.html {
            attributes("lang" to "en")
            comment(RenderCredit.Text)
            element("head") {
                element("meta", "charset" to "utf-8")
                element("meta", "name" to "viewport", "content" to "width=device-width, initial-scale=1")
                element("meta", "http-equiv" to "refresh", "content" to "1")
                element("title") { text("X screen text report") }
                element("style") { text(textCss()) }
            }
            element("body") {
                element("main") {
                    element("h1") { text("X screen text report") }
                    element("pre") { text(plain(snapshot)) }
                    element("footer") {
                        text("by ")
                        element("a", "href" to "https://github.com/jonnyzzz/x") { text("@jonnyzzz") }
                        text(" ")
                        element("a", "href" to "https://linkedin.com/in/jonnyzzz") { text("https://linkedin.com/in/jonnyzzz") }
                    }
                }
            }
        }

    fun plain(snapshot: XScreenSnapshot): String =
        buildString {
            appendLine("Screen: ${snapshot.width} x ${snapshot.height}")
            appendLine("DPI: ${snapshot.dpi}")
            appendLine("Physical size: ${snapshot.widthMillimeters} x ${snapshot.heightMillimeters} mm")
            appendLine("Windows: ${snapshot.windows.size}")
            appendLine("Mapped windows: ${snapshot.windows.count { it.mapped }}")
            appendLine("Pixmaps: ${snapshot.pixmaps.size}")
            appendLine("Painted pixmaps: ${snapshot.pixmaps.count { it.painted }}")
            appendLine("Cursors: ${snapshot.cursors.size}")
            appendLine("Focus: ${snapshot.windows.firstOrNull { it.focused }?.idHex ?: "none"}")
            appendLine("Pointer: ${snapshot.pointer.x},${snapshot.pointer.y} mask=${snapshot.pointer.mask} window=${snapshot.pointer.windowIdHex}")
            appendLine()
            appendLine("Window hierarchy and geometry:")
            for (window in snapshot.windows) {
                append("- ")
                append(window.idHex)
                append(" parent=")
                append(window.parentIdHex)
                append(" label=\"")
                append(window.label)
                append("\" geometry=")
                append(window.x).append(',').append(window.y)
                append(' ').append(window.width).append('x').append(window.height)
                append(" class=").append(window.className)
                append(" depth=").append(window.depth)
                append(" visual=").append(window.visualHex)
                append(" backgroundPixel=").append(window.backgroundPixel)
                append(" backgroundPixmap=").append(window.backgroundPixmapIdHex ?: "none")
                append(" borderPixel=").append(window.borderPixel)
                append(" borderPixmap=").append(window.borderPixmapIdHex ?: "none")
                append(" bitGravity=").append(window.bitGravity)
                append(" winGravity=").append(window.winGravity)
                append(" backingStore=").append(window.backingStore)
                append(" backingPlanes=").append(window.backingPlanes)
                append(" backingPixel=").append(window.backingPixel)
                append(" saveUnder=").append(window.saveUnder)
                append(" overrideRedirect=").append(window.overrideRedirect)
                append(" colormap=").append(window.colormapIdHex ?: "none")
                append(" cursor=").append(window.cursorIdHex ?: "none")
                append(" mapped=").append(window.mapped)
                append(" focused=").append(window.focused)
                append(" stack=").append(window.stackingIndex)
                appendLine()
            }
            appendLine()
            appendLine("Overlap and focus:")
            if (snapshot.overlaps.isEmpty()) {
                appendLine("- No mapped non-root windows overlap.")
            } else {
                for (overlap in snapshot.overlaps) {
                    append("- ")
                    append(overlap.upperWindowIdHex)
                    append(" overlaps ")
                    append(overlap.lowerWindowIdHex)
                    append(" at ")
                    append(overlap.x).append(',').append(overlap.y)
                    append(' ').append(overlap.width).append('x').append(overlap.height)
                    appendLine()
                }
            }
            appendLine()
            appendLine("Offscreen pixmaps:")
            if (snapshot.pixmaps.isEmpty()) {
                appendLine("- None.")
            } else {
                for (pixmap in snapshot.pixmaps.sortedByDescending { it.width * it.height }.take(30)) {
                    append("- ")
                    append(pixmap.idHex)
                    append(" geometry=")
                    append(pixmap.width).append('x').append(pixmap.height)
                    append(" depth=").append(pixmap.depth)
                    append(" painted=").append(pixmap.painted)
                    if (pixmap.retainedPictureIdHex != null) {
                        append(" retained-picture=")
                        append(pixmap.retainedPictureIdHex)
                    }
                    if (pixmap.pictureIdHexes.isNotEmpty()) {
                        append(" pictures=")
                        append(pixmap.pictureIdHexes.joinToString(","))
                    }
                    if (pixmap.matchingWindowIdHexes.isNotEmpty()) {
                        append(" candidate-for=")
                        append(pixmap.matchingWindowIdHexes.joinToString(","))
                    }
                    if (pixmap.pixelRows.isNotEmpty()) {
                        append(" rows=")
                        append(pixmap.pixelRows.joinToString("/"))
                    }
                    if (pixmap.coreDrawingPaintCount > 0) {
                        append(" corePaints=")
                        append(pixmap.coreDrawingPaintCount)
                        pixmap.firstCoreDrawingPaint?.let { first ->
                            append(" firstCore=")
                            appendCoreDrawingPaintSummary(first)
                            if (first.rectangles.isNotEmpty()) {
                                append('@')
                                append(first.rectangles.joinToString(",", prefix = "[", postfix = "]") { it.toRegionString() })
                            }
                        }
                        pixmap.lastCoreDrawingPaint?.let { last ->
                            append(" lastCore=")
                            appendCoreDrawingPaintSummary(last)
                            if (last.rectangles.isNotEmpty()) {
                                append('@')
                                append(last.rectangles.joinToString(",", prefix = "[", postfix = "]") { it.toRegionString() })
                            }
                            last.putImage?.let { putImage ->
                                append(" putImageCrc32=")
                                append(putImage.crc32Hex)
                                appendPutImageSummary(putImage)
                            }
                        }
                    }
                    if (pixmap.renderOperations.isNotEmpty()) {
                        val lastRender = pixmap.renderOperations.last()
                        append(" renderOps=")
                        append(pixmap.renderOperations.size)
                        append(" lastRender=#")
                        append(lastRender.id)
                        append('/')
                        append(lastRender.operation)
                        lastRender.result?.let { result ->
                            append(" result=")
                            append(result.width)
                            append('x')
                            append(result.height)
                            append(" crc32=")
                            append(result.crc32Hex)
                        }
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("Cursors:")
            if (snapshot.cursors.isEmpty()) {
                appendLine("- None.")
            } else {
                for (cursor in snapshot.cursors) {
                    append("- ")
                    append(cursor.idHex)
                    append(" kind=").append(cursor.kind)
                    cursor.sourcePixmapIdHex?.let { append(" sourcePixmap=").append(it) }
                    cursor.maskPixmapIdHex?.let { append(" maskPixmap=").append(it) }
                    cursor.sourceFontIdHex?.let { append(" sourceFont=").append(it) }
                    cursor.maskFontIdHex?.let { append(" maskFont=").append(it) }
                    cursor.sourceChar?.let { append(" sourceChar=0x").append(it.toString(16)) }
                    cursor.maskChar?.let { append(" maskChar=0x").append(it.toString(16)) }
                    cursor.sourcePictureIdHex?.let { append(" sourcePicture=").append(it) }
                    cursor.name?.let { append(" name=").append(it).append(" atom=").append(cursor.nameAtomHex ?: "None") }
                    if (cursor.animationElements.isNotEmpty()) {
                        append(" animation=")
                        append(cursor.animationElements.joinToString(",") { "${it.cursorIdHex}@${it.delayMilliseconds}ms" })
                    }
                    cursor.hotspotX?.let { append(" hotspot=").append(it).append(',').append(cursor.hotspotY ?: 0) }
                    append(" foreground=").append(cursor.foregroundHex)
                    append(" background=").append(cursor.backgroundHex)
                    appendLine()
                }
            }
            appendLine()
            appendLine("Request counts:")
            if (snapshot.requestCounts.isEmpty()) {
                appendLine("- None.")
            } else {
                for (request in snapshot.requestCounts.sortedByDescending { it.count }.take(20)) {
                    append("- ")
                    append(request.name)
                    append(": ")
                    append(request.count)
                    appendLine()
                }
            }
            appendLine()
            appendLine("Property operations:")
            if (snapshot.propertyOperations.isEmpty()) {
                appendLine("- None.")
            } else {
                for (operation in snapshot.propertyOperations.takeLast(160).asReversed()) {
                    append("- #")
                    append(operation.id)
                    append(' ')
                    append(operation.operation)
                    if (operation.detail.isNotBlank()) {
                        append(' ')
                        append(operation.detail)
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("Extension queries:")
            if (snapshot.extensionQueries.isEmpty()) {
                appendLine("- None.")
            } else {
                for (query in snapshot.extensionQueries.takeLast(20).asReversed()) {
                    append("- #")
                    append(query.id)
                    append(' ')
                    append(query.name)
                    append(" supported=")
                    append(query.supported)
                    appendLine()
                }
            }
            appendLine()
            appendLine("GLX contexts:")
            if (snapshot.glxContexts.isEmpty()) {
                appendLine("- None.")
            } else {
                for (context in snapshot.glxContexts) {
                    append("- ")
                    append(context.idHex)
                    append(" fbConfig=")
                    append(context.fbConfigIdHex)
                    append(" screen=")
                    append(context.screen)
                    append(" renderType=")
                    append(context.renderTypeHex)
                    append(" version=")
                    if (context.contextMajorVersion != null || context.contextMinorVersion != null) {
                        append(context.contextMajorVersion ?: 0)
                        append('.')
                        append(context.contextMinorVersion ?: 0)
                    } else {
                        append("unspecified")
                    }
                    append(" profileMask=")
                    append(context.profileMaskHex)
                    append(" direct=")
                    append(context.direct)
                    append(" draw=")
                    append(context.currentDrawDrawableIdHex ?: "none")
                    append(" read=")
                    append(context.currentReadDrawableIdHex ?: "none")
                    appendLine()
                }
            }
            appendLine()
            appendLine("GLX pixmaps:")
            if (snapshot.glxPixmaps.isEmpty()) {
                appendLine("- None.")
            } else {
                for (pixmap in snapshot.glxPixmaps) {
                    append("- ")
                    append(pixmap.idHex)
                    append(" pixmap=")
                    append(pixmap.pixmapIdHex)
                    append(" visual=")
                    append(pixmap.visualIdHex)
                    append(" fbConfig=")
                    append(pixmap.fbConfigIdHex)
                    append(" screen=")
                    append(pixmap.screen)
                    append(" size=")
                    append(pixmap.width)
                    append('x')
                    append(pixmap.height)
                    append(" depth=")
                    append(pixmap.depth)
                    append(" eventMask=0x")
                    append(pixmap.eventMask.toUInt().toString(16))
                    append(" textureTarget=0x")
                    append(pixmap.textureTarget.toUInt().toString(16))
                    appendLine()
                }
            }
            appendLine()
            appendLine("GLX windows:")
            if (snapshot.glxWindows.isEmpty()) {
                appendLine("- None.")
            } else {
                for (window in snapshot.glxWindows) {
                    append("- ")
                    append(window.idHex)
                    append(" window=")
                    append(window.windowIdHex)
                    append(" fbConfig=")
                    append(window.fbConfigIdHex)
                    append(" screen=")
                    append(window.screen)
                    append(" size=")
                    append(window.width)
                    append('x')
                    append(window.height)
                    append(" eventMask=0x")
                    append(window.eventMask.toUInt().toString(16))
                    appendLine()
                }
            }
            appendLine()
            appendLine("GLX pbuffers:")
            if (snapshot.glxPbuffers.isEmpty()) {
                appendLine("- None.")
            } else {
                for (pbuffer in snapshot.glxPbuffers) {
                    append("- ")
                    append(pbuffer.idHex)
                    append(" fbConfig=")
                    append(pbuffer.fbConfigIdHex)
                    append(" screen=")
                    append(pbuffer.screen)
                    append(" size=")
                    append(pbuffer.width)
                    append('x')
                    append(pbuffer.height)
                    append(" eventMask=0x")
                    append(pbuffer.eventMask.toUInt().toString(16))
                    appendLine()
                }
            }
            appendLine()
            appendLine("Unsupported requests:")
            if (snapshot.unsupportedRequests.isEmpty()) {
                appendLine("- None.")
            } else {
                for (request in snapshot.unsupportedRequests.takeLast(20).asReversed()) {
                    append("- #")
                    append(request.id)
                    append(' ')
                    append(request.name)
                    append(" opcode=")
                    append(request.opcode)
                    append(" minor=")
                    append(request.minorOpcode)
                    appendLine()
                }
            }
            appendLine()
            appendLine("GLX operations:")
            if (snapshot.glxOperations.isEmpty()) {
                appendLine("- None.")
            } else {
                for (operation in snapshot.glxOperations.takeLast(MaxGlxOperationsInTextReport).asReversed()) {
                    append("- #")
                    append(operation.id)
                    append(' ')
                    append(operation.operation)
                    append(" minor=")
                    append(operation.minorOpcode)
                    if (operation.detail.isNotBlank()) {
                        append(" ")
                        append(operation.detail)
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("RENDER operations:")
            if (snapshot.renderOperations.isEmpty()) {
                appendLine("- None.")
            } else {
                for (operation in snapshot.renderOperations.takeLast(MaxRenderOperationsInTextReport).asReversed()) {
                    appendRenderOperationLine(operation)
                }
            }
            appendLine()
            appendMappedRootChildRenderBands(snapshot)
            appendLine()
            appendLine("Recent core text commands:")
            val coreText = snapshot.drawings
                .filter { it.kind == XDrawingKind.Text && it.text.isNotEmpty() && it.textOrigin?.decodedApplicationText == true }
                .takeLast(30)
                .asReversed()
            if (coreText.isEmpty()) {
                appendLine("- None.")
            } else {
                for (drawing in coreText) {
                    append("- drawable=0x")
                    append(drawing.drawableId.toUInt().toString(16))
                    append(" baselines=")
                    append(drawing.points.joinToString(",", prefix = "[", postfix = "]") { "${it.x}:${it.y}" })
                    append(" painted=")
                    append(drawing.framebufferPainted)
                    append(" origin=")
                    append(drawing.textOrigin?.name ?: "unknown")
                    drawing.drawableGeneration?.let { append(" generation=").append(it) }
                    drawing.sourceDrawableId?.let { append(" source=0x").append(it.toUInt().toString(16)) }
                    drawing.sourceDrawableGeneration?.let { append(" sourceGeneration=").append(it) }
                    append(" text=")
                    append(reportString(drawing.text))
                    appendLine()
                }
            }
            appendLine()
            appendLine("Recent PutImage commands:")
            val putImages = snapshot.drawings.filter { it.putImage != null }.takeLast(30).asReversed()
            if (putImages.isEmpty()) {
                appendLine("- None.")
            } else {
                for (drawing in putImages) {
                    val metadata = drawing.putImage ?: continue
                    val rectangle = drawing.rectangles.firstOrNull()
                    append("- drawable=0x")
                    append(drawing.drawableId.toUInt().toString(16))
                    if (rectangle != null) {
                        append(" at=")
                        append(rectangle.x)
                        append(',')
                        append(rectangle.y)
                    }
                    append(" size=")
                    append(metadata.width)
                    append('x')
                    append(metadata.height)
                    append(" format=")
                    append(metadata.format)
                    append(" depth=")
                    append(metadata.depth)
                    append(" leftPad=")
                    append(metadata.leftPad)
                    append(" dataBytes=")
                    append(metadata.dataBytes)
                    append(" rowStride=")
                    append(metadata.rowStrideBytes)
                    metadata.planeBytes?.let { append(" planeBytes=").append(it) }
                    append(" crc32=")
                    append(metadata.crc32Hex)
                    append(" raw=")
                    append(metadata.rawSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                    append(" decoded=")
                    append(metadata.decodedPixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                    append(" tileRaw=")
                    append(metadata.rawTileSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                    append(" tileDecoded=")
                    append(metadata.decodedTilePixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                    append(" rowRaw=")
                    appendPutImageRows(metadata.rawRowSampleHex)
                    append(" rowDecoded=")
                    appendPutImageRows(metadata.decodedRowPixelSampleHex)
                    appendPutImageSummary(metadata, includeFormat = false)
                    appendLine()
                }
            }
            appendLine()
            appendLine("Recent core copy commands:")
            val coreCopies = snapshot.drawings
                .filter { it.kind == XDrawingKind.CopyArea || it.kind == XDrawingKind.CopyPlane }
                .takeLast(MaxCoreCopyCommandsInTextReport)
                .asReversed()
            if (coreCopies.isEmpty()) {
                appendLine("- None.")
            } else {
                for (drawing in coreCopies) {
                    append("- ")
                    append(drawing.kind.name)
                    append(" drawable=0x")
                    append(drawing.drawableId.toUInt().toString(16))
                    append(" source=")
                    append(drawing.sourceDrawableId?.let { "0x${it.toUInt().toString(16)}" } ?: "none")
                    append(" foreground=")
                    append(pixelHex(drawing.foreground))
                    if (drawing.kind == XDrawingKind.CopyPlane) {
                        append(" background=")
                        append(pixelHex(drawing.background))
                    }
                    if (
                        drawing.clipXOrigin != 0 ||
                        drawing.clipYOrigin != 0 ||
                        drawing.clipMaskPixmapId != null ||
                        drawing.gcClipRectangles != null ||
                        drawing.drawableClipRectangles != null
                    ) {
                        append(" clipOrigin=")
                        append(drawing.clipXOrigin)
                        append(',')
                        append(drawing.clipYOrigin)
                    }
                    if (drawing.clipMaskPixmapId != null) {
                        append(" clipMask=0x")
                        append(drawing.clipMaskPixmapId.toUInt().toString(16))
                    }
                    if (drawing.clipMaskRows.isNotEmpty()) {
                        append(" clipRows=")
                        append(drawing.clipMaskRows.joinToString("/"))
                    }
                    if (drawing.gcClipRectangles != null) {
                        append(" gcClip=")
                        append(drawing.gcClipRectangles.toRegionSummary())
                    }
                    if (drawing.drawableClipRectangles != null) {
                        append(" drawableClip=")
                        append(drawing.drawableClipRectangles.toRegionSummary())
                    }
                    append(" painted=")
                    append(drawing.framebufferPainted)
                    if (drawing.drawableGeneration != null) {
                        append(" generation=")
                        append(drawing.drawableGeneration)
                    }
                    if (drawing.sourceDrawableGeneration != null) {
                        append(" sourceGeneration=")
                        append(drawing.sourceDrawableGeneration)
                    }
                    if (drawing.rectangles.isNotEmpty()) {
                        append(" rects=")
                        append(drawing.rectangles.joinToString(",") { it.toRegionString() })
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("RENDER pictures:")
            if (snapshot.renderPictures.isEmpty()) {
                appendLine("- None.")
            } else {
                for (picture in snapshot.renderPictures.takeLast(30).asReversed()) {
                    append("- ")
                    append(picture.idHex)
                    append(" drawable=")
                    append(picture.drawableIdHex)
                    append(" kind=")
                    append(picture.drawableKind)
                    append(" format=")
                    append("0x${picture.format.toUInt().toString(16)}")
                    append(" repeat=")
                    append(picture.repeatName)
                    if (picture.alphaMap != 0) append(" alphaMap=").append(picture.alphaMapHex)
                    if (picture.alphaXOrigin != 0 || picture.alphaYOrigin != 0) append(" alphaOrigin=").append(picture.alphaXOrigin).append(',').append(picture.alphaYOrigin)
                    if (picture.clipXOrigin != 0 || picture.clipYOrigin != 0) append(" clipOrigin=").append(picture.clipXOrigin).append(',').append(picture.clipYOrigin)
                    if (picture.clipMask != 0) append(" clipMask=").append(picture.clipMaskHex)
                    appendRenderPictureClipRectangleSummary(picture)
                    if (picture.graphicsExposure) append(" graphicsExposure=true")
                    if (picture.subwindowMode != 0) append(" subwindowMode=").append(picture.subwindowMode)
                    if (picture.polyEdge != XRender.DefaultPolyEdge) append(" polyEdge=").append(picture.polyEdge)
                    if (picture.polyMode != XRender.DefaultPolyMode) append(" polyMode=").append(picture.polyMode)
                    if (picture.dither != 0) append(" dither=").append(picture.ditherHex)
                    if (picture.componentAlpha) append(" componentAlpha=true")
                    append(" transform=")
                    append(picture.transformHex.joinToString(",", prefix = "[", postfix = "]"))
                    if (picture.filterName != null) {
                        append(" filter=")
                        append(picture.filterName)
                        append(" values=")
                        append(picture.filterValueHex.joinToString(",", prefix = "[", postfix = "]"))
                    }
                    if (picture.solidPixel != null) {
                        append(" solid=")
                        append(pixelHex(picture.solidPixel))
                    }
                    picture.linearGradient?.let { gradient ->
                        append(" linearGradient=")
                        append(gradient.p1Hex)
                        append("->")
                        append(gradient.p2Hex)
                        append(" stops=")
                        append(gradient.stopHex.joinToString(",", prefix = "[", postfix = "]"))
                        append(" colors=")
                        append(gradient.colorHex.joinToString(",", prefix = "[", postfix = "]"))
                        append(" rawColors=")
                        append(gradient.rawColorHex.joinToString(",", prefix = "[", postfix = "]"))
                    }
                    picture.radialGradient?.let { gradient ->
                        append(" radialGradient=")
                        append(gradient.innerHex)
                        append("->")
                        append(gradient.outerHex)
                        append(" stops=")
                        append(gradient.stopHex.joinToString(",", prefix = "[", postfix = "]"))
                        append(" colors=")
                        append(gradient.colorHex.joinToString(",", prefix = "[", postfix = "]"))
                        append(" rawColors=")
                        append(gradient.rawColorHex.joinToString(",", prefix = "[", postfix = "]"))
                    }
                    picture.conicalGradient?.let { gradient ->
                        append(" conicalGradient=")
                        append(gradient.centerHex)
                        append(" angle=")
                        append(gradient.angleHex)
                        append(" stops=")
                        append(gradient.stopHex.joinToString(",", prefix = "[", postfix = "]"))
                        append(" colors=")
                        append(gradient.colorHex.joinToString(",", prefix = "[", postfix = "]"))
                        append(" rawColors=")
                        append(gradient.rawColorHex.joinToString(",", prefix = "[", postfix = "]"))
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("Input operations:")
            if (snapshot.inputOperations.isEmpty()) {
                appendLine("- None.")
            } else {
                for (operation in snapshot.inputOperations.takeLast(20).asReversed()) {
                    append("- #")
                    append(operation.id)
                    append(' ')
                    append(operation.kind)
                    append(' ')
                    append(operation.button)
                    append(" at ")
                    append(operation.x).append(',').append(operation.y)
                    append(" target=")
                    append(operation.targetWindowIdHex ?: "none")
                    append(" delivered=")
                    append(operation.deliveredEvents)
                    appendLine()
                }
            }
            appendLine()
            appendLine(RenderCredit.Text)
        }

    private fun StringBuilder.appendMappedRootChildRenderBands(snapshot: XScreenSnapshot) {
        val frame = snapshot.windows
            .filter { it.id != X11Ids.RootWindow && it.parentId == X11Ids.RootWindow && it.mapped }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (frame == null) {
            appendLine("RENDER operations intersecting top mapped root-child band:")
            appendLine("- None.")
            return
        }

        val bands = listOf(
            "top" to XRectangleCommand(
                x = frame.x,
                y = frame.y,
                width = frame.width,
                height = minOf(TopMappedRootChildBandHeight, frame.height),
            ),
            "right" to XRectangleCommand(
                x = frame.x + maxOf(0, frame.width - minOf(RightMappedRootChildBandWidth, frame.width)),
                y = frame.y,
                width = minOf(RightMappedRootChildBandWidth, frame.width),
                height = frame.height,
            ),
            "bottom" to XRectangleCommand(
                x = frame.x,
                y = frame.y + maxOf(0, frame.height - minOf(BottomMappedRootChildBandHeight, frame.height)),
                width = frame.width,
                height = minOf(BottomMappedRootChildBandHeight, frame.height),
            ),
        )

        for ((band, region) in bands) {
            appendRenderOperationsIntersectingMappedRootChildBand(snapshot, frame, band, region)
            appendLine()
        }
    }

    private fun StringBuilder.appendRenderOperationsIntersectingMappedRootChildBand(
        snapshot: XScreenSnapshot,
        frame: XWindowSnapshot,
        band: String,
        region: XRectangleCommand,
    ) {
        append("RENDER operations intersecting ")
        append(band)
        appendLine(" mapped root-child band:")
        append("- region=")
        appendRegion(region)
        append(" window=")
        append(frame.idHex)
        appendLine()

        val matched = snapshot.renderOperations
            .mapNotNull { operation ->
                val rootRegions = renderOperationRootRegions(snapshot, operation)
                    .filter { it.intersects(region) }
                if (rootRegions.isEmpty()) null else operation to rootRegions
            }
            .takeLast(MaxRegionalRenderOperationsInTextReport)
            .asReversed()
        if (matched.isEmpty()) {
            appendLine("- None.")
            return
        }
        for ((operation, rootRegions) in matched) {
            appendRenderOperationLine(operation, rootRegions)
        }
    }

    private fun StringBuilder.appendRenderOperationLine(
        operation: XRenderOperation,
        rootRegions: List<XRectangleCommand> = emptyList(),
    ) {
        append("- #")
        append(operation.id)
        append(' ')
        append(operation.operation)
        append(" minor=")
        append(operation.minorOpcode)
        if (rootRegions.isNotEmpty()) {
            append(" root=")
            append(rootRegions.joinToString(",") { it.toRegionString() })
        }
        operation.provenance?.destinationRegion?.let { region ->
            append(" local=")
            appendRegion(region)
        }
        if (operation.detail.isNotBlank()) {
            append(" ")
            append(operation.detail)
        }
        operation.provenance?.let { provenance ->
            appendRenderOperationProvenance(provenance)
        }
        appendLine()
    }

    private fun renderOperationRootRegions(snapshot: XScreenSnapshot, operation: XRenderOperation): List<XRectangleCommand> {
        val provenance = operation.provenance ?: return emptyList()
        val local = provenance.destinationRegion ?: return emptyList()
        if (local.width <= 0 || local.height <= 0) return emptyList()
        val drawableId = provenance.destination?.drawableId ?: return emptyList()
        val window = snapshot.windows.firstOrNull { it.id == drawableId }
        if (window != null) return listOf(local.translated(window.x, window.y))

        val pixmap = snapshot.pixmaps.firstOrNull { it.id == drawableId } ?: return emptyList()
        val matchingWindowIds = pixmap.provenanceMatchingWindowIds.ifEmpty { pixmap.matchingWindowIds }
        return matchingWindowIds.mapNotNull { windowId ->
            snapshot.windows.firstOrNull { it.id == windowId }?.let { local.translated(it.x, it.y) }
        }
    }

    private fun XRectangleCommand.translated(dx: Int, dy: Int): XRectangleCommand =
        XRectangleCommand(x = x + dx, y = y + dy, width = width, height = height)

    private fun XRectangleCommand.intersects(other: XRectangleCommand): Boolean =
        width > 0 &&
            height > 0 &&
            other.width > 0 &&
            other.height > 0 &&
            x < other.x + other.width &&
            other.x < x + width &&
            y < other.y + other.height &&
            other.y < y + height

    private fun StringBuilder.appendRegion(region: XRectangleCommand) {
        append(region.toRegionString())
    }

    private fun XRectangleCommand.toRegionString(): String =
        "$x,$y ${width}x$height"

    private fun List<XRectangleCommand>.toRegionSummary(totalCount: Int = size): String =
        take(MaxCoreCopyClipRectanglesInTextReport).joinToString(",") { it.toRegionString() } +
            if (totalCount > MaxCoreCopyClipRectanglesInTextReport) ",...(+${totalCount - MaxCoreCopyClipRectanglesInTextReport})" else ""

    private fun StringBuilder.appendRenderPictureClipRectangleSummary(picture: XRenderPictureSnapshot) {
        append(" clips=")
        append(picture.clipRectangles)
        append(" clipsPresent=")
        append(picture.clipRectanglesPresent)
        append(" clipsRetained=")
        append(picture.clipRectanglesRetained)
        append(" clipsComplete=")
        append(picture.clipRectanglesComplete)
        append(" clipDetails=[")
        append(picture.clipRectangleDetails.toRegionSummary(picture.clipRectangles))
        append(']')
    }

    private fun textCss(): String =
        """
        body { margin: 0; padding: 24px; background: #111318; color: #e7e9ee; font-family: system-ui, sans-serif; }
        main { max-width: 980px; margin: 0 auto; }
        h1 { margin-top: 0; font-size: 22px; }
        pre { white-space: pre-wrap; background: #1b1f29; border: 1px solid #303642; padding: 16px; overflow: auto; }
        footer { color: #aab2c0; font-size: 12px; margin-top: 18px; }
        """.trimIndent()

    private fun StringBuilder.appendRenderOperationProvenance(provenance: XRenderOperationProvenance) {
        fun appendPicture(label: String, picture: XRenderPictureSnapshot) {
            append(" ")
            append(label)
            append("=")
            append(picture.idHex)
            append("/")
            append(picture.drawableKind)
            append(" repeat=")
            append(picture.repeatName)
            if (picture.filterName != null) {
                append(" filter=")
                append(picture.filterName)
            }
            appendRenderPictureClipRectangleSummary(picture)
            if (picture.transform != IdentityTransform) {
                append(" transform=")
                append(picture.transformHex.joinToString(",", prefix = "[", postfix = "]"))
            }
            picture.solidPixel?.let { append(" solid=").append(pixelHex(it)) }
            picture.linearGradient?.let { gradient ->
                append(" linear=")
                append(gradient.p1Hex)
                append("->")
                append(gradient.p2Hex)
                append(" stops=")
                append(gradient.stopHex.joinToString(",", prefix = "[", postfix = "]"))
                append(" colors=")
                append(gradient.colorHex.joinToString(",", prefix = "[", postfix = "]"))
                append(" rawColors=")
                append(gradient.rawColorHex.joinToString(",", prefix = "[", postfix = "]"))
            }
            picture.radialGradient?.let { gradient ->
                append(" radial=")
                append(gradient.innerHex)
                append("->")
                append(gradient.outerHex)
                append(" stops=")
                append(gradient.stopHex.joinToString(",", prefix = "[", postfix = "]"))
                append(" colors=")
                append(gradient.colorHex.joinToString(",", prefix = "[", postfix = "]"))
                append(" rawColors=")
                append(gradient.rawColorHex.joinToString(",", prefix = "[", postfix = "]"))
            }
            picture.conicalGradient?.let { gradient ->
                append(" conical=")
                append(gradient.centerHex)
                append(" angle=")
                append(gradient.angleHex)
                append(" stops=")
                append(gradient.stopHex.joinToString(",", prefix = "[", postfix = "]"))
                append(" colors=")
                append(gradient.colorHex.joinToString(",", prefix = "[", postfix = "]"))
                append(" rawColors=")
                append(gradient.rawColorHex.joinToString(",", prefix = "[", postfix = "]"))
            }
        }
        provenance.source?.let { appendPicture("source", it) }
        provenance.mask?.let { appendPicture("mask", it) }
        provenance.destination?.let { appendPicture("destination", it) }
        provenance.sourcePopulation?.let { population ->
            append(" sourcePopulation=")
            append(population.drawableIdHex)
            append("#")
            append(population.generation)
            append(" paints=")
            append(population.paintCount)
            population.firstPaint?.let { first ->
                append(" first=#")
                append(first.id)
                append('/')
                append(first.operation)
            }
            population.lastPaint?.let { last ->
                append(" last=#")
                append(last.id)
                append('/')
                append(last.operation)
            }
            if (population.drawingPaintCount > 0) {
                append(" drawings=")
                append(population.drawingPaintCount)
                population.firstDrawingPaint?.let { first ->
                    append(" firstDrawing=")
                    append(first.kind.name)
                    append('@')
                    append(first.rectangles.joinToString(",", prefix = "[", postfix = "]") { rectangle ->
                        "${rectangle.x},${rectangle.y} ${rectangle.width}x${rectangle.height}"
                    })
                }
                population.lastDrawingPaint?.let { last ->
                    append(" lastDrawing=")
                    append(last.kind.name)
                    last.putImage?.let { putImage ->
                        append(" putImageCrc32=")
                        append(putImage.crc32Hex)
                        appendPutImageSummary(putImage)
                    }
                }
            }
            population.lastPaint?.result?.let { result ->
                append(" lastResult=")
                append(result.width)
                append('x')
                append(result.height)
                append(" crc32=")
                append(result.crc32Hex)
            }
            population.framebuffer?.let { framebuffer ->
                append(" framebuffer=")
                append(framebuffer.width)
                append('x')
                append(framebuffer.height)
                append(" crc32=")
                append(framebuffer.crc32Hex)
                append(" pixels=")
                append(framebuffer.pixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                if (framebuffer.pointSampleHex.isNotEmpty()) {
                    append(" pointPixels=")
                    append(framebuffer.pointSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                }
            }
            population.lastPaint?.sourcePopulation?.let { producer ->
                append(" producerSourcePopulation=")
                append(producer.drawableIdHex)
                append("#")
                append(producer.generation)
                append(" paints=")
                append(producer.paintCount)
                if (producer.drawingPaintCount > 0) {
                    append(" drawings=")
                    append(producer.drawingPaintCount)
                    producer.lastDrawingPaint?.let { last ->
                        append(" lastDrawing=")
                        append(last.kind.name)
                        last.putImage?.let { putImage ->
                            append(" putImageCrc32=")
                            append(putImage.crc32Hex)
                            appendPutImageSummary(putImage)
                        }
                    }
                }
                producer.framebuffer?.let { framebuffer ->
                    append(" producerFramebuffer=")
                    append(framebuffer.width)
                    append('x')
                    append(framebuffer.height)
                    append(" crc32=")
                    append(framebuffer.crc32Hex)
                    append(" pixels=")
                    append(framebuffer.pixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                    if (framebuffer.pointSampleHex.isNotEmpty()) {
                        append(" pointPixels=")
                        append(framebuffer.pointSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                    }
                }
            }
        }
        provenance.maskPopulation?.let { population ->
            append(" maskPopulation=")
            append(population.drawableIdHex)
            append("#")
            append(population.generation)
            append(" paints=")
            append(population.paintCount)
            if (population.drawingPaintCount > 0) {
                append(" drawings=")
                append(population.drawingPaintCount)
                population.lastDrawingPaint?.let { last ->
                    append(" lastDrawing=")
                    append(last.kind.name)
                    last.putImage?.let { putImage ->
                        append(" putImageCrc32=")
                        append(putImage.crc32Hex)
                        appendPutImageSummary(putImage)
                    }
                }
            }
            population.framebuffer?.let { framebuffer ->
                append(" maskFramebuffer=")
                append(framebuffer.width)
                append('x')
                append(framebuffer.height)
                append(" crc32=")
                append(framebuffer.crc32Hex)
                append(" pixels=")
                append(framebuffer.pixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                if (framebuffer.pointSampleHex.isNotEmpty()) {
                    append(" pointPixels=")
                    append(framebuffer.pointSampleHex.joinToString(",", prefix = "[", postfix = "]"))
                }
            }
        }
        provenance.freed?.let { appendPicture("freed", it) }
        provenance.result?.let { result ->
            append(" result=")
            append(result.width)
            append('x')
            append(result.height)
            append(" crc32=")
            append(result.crc32Hex)
            append(" pixels=")
            append(result.pixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
            if (result.pointSampleHex.isNotEmpty()) {
                append(" pointPixels=")
                append(result.pointSampleHex.joinToString(",", prefix = "[", postfix = "]"))
            }
        }
    }

    private fun pixelHex(pixel: Int): String = "0x${pixel.toUInt().toString(16).padStart(8, '0')}"

    private fun reportString(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

    private fun StringBuilder.appendCoreDrawingPaintSummary(paint: XCoreDrawablePaintSnapshot) {
        append(paint.kind.name)
        append("(fg=")
        append(paint.foregroundHex)
        append(",bg=")
        append(paint.backgroundHex)
        append(",lw=")
        append(paint.lineWidth)
        append(",ls=")
        append(paint.lineStyle)
        append(",cap=")
        append(paint.capStyle)
        if (paint.points.isNotEmpty()) {
            append(",points=")
            append(paint.points.joinToString(";", prefix = "[", postfix = "]") { "${it.x},${it.y}" })
        }
        append(')')
    }

    private fun StringBuilder.appendPutImageSummary(
        putImage: XPutImageMetadata,
        includeFormat: Boolean = true,
    ) {
        append(" putImage=")
        if (includeFormat) {
            append("format=")
            append(putImage.format)
            append(',')
        }
        append("depth=")
        append(putImage.depth)
        append(",leftPad=")
        append(putImage.leftPad)
        append(",size=")
        append(putImage.width)
        append('x')
        append(putImage.height)
        append(",dataBytes=")
        append(putImage.dataBytes)
        append(",rowStride=")
        append(putImage.rowStrideBytes)
        putImage.planeBytes?.let { planeBytes ->
            append(",planeBytes=")
            append(planeBytes)
        }
        append(",crc32=")
        append(putImage.crc32Hex)
        if (putImage.rawSampleHex.isNotEmpty()) {
            append(",raw=")
            append(putImage.rawSampleHex.joinToString(",", prefix = "[", postfix = "]"))
        }
        if (putImage.decodedPixelSampleHex.isNotEmpty()) {
            append(",decoded=")
            append(putImage.decodedPixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
        }
        if (putImage.rawTileSampleHex.isNotEmpty()) {
            append(",tileRaw=")
            append(putImage.rawTileSampleHex.joinToString(",", prefix = "[", postfix = "]"))
        }
        if (putImage.decodedTilePixelSampleHex.isNotEmpty()) {
            append(",tileDecoded=")
            append(putImage.decodedTilePixelSampleHex.joinToString(",", prefix = "[", postfix = "]"))
        }
        if (putImage.rawRowSampleHex.isNotEmpty()) {
            append(",rowRaw=")
            appendPutImageRows(putImage.rawRowSampleHex)
        }
        if (putImage.decodedRowPixelSampleHex.isNotEmpty()) {
            append(",rowDecoded=")
            appendPutImageRows(putImage.decodedRowPixelSampleHex)
        }
    }

    private fun StringBuilder.appendPutImageRows(rows: List<List<String>>) {
        rows.joinTo(this, separator = ",", prefix = "[", postfix = "]") { row ->
            row.joinToString(",", prefix = "[", postfix = "]")
        }
    }
}
