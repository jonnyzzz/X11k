package org.jonnyzzz.xserver

internal object TextScreenRenderer {
    private const val MaxGlxOperationsInTextReport = 200
    private const val MaxRenderOperationsInTextReport = 30
    private const val MaxRegionalRenderOperationsInTextReport = 200
    private const val TopMappedRootChildBandHeight = 120

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
            appendTopMappedRootChildRenderBand(snapshot)
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
                    append(" clips=")
                    append(picture.clipRectangles)
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

    private fun StringBuilder.appendTopMappedRootChildRenderBand(snapshot: XScreenSnapshot) {
        appendLine("RENDER operations intersecting top mapped root-child band:")
        val frame = snapshot.windows
            .filter { it.id != X11Ids.RootWindow && it.parentId == X11Ids.RootWindow && it.mapped }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (frame == null) {
            appendLine("- None.")
            return
        }
        val region = XRectangleCommand(
            x = frame.x,
            y = frame.y,
            width = frame.width,
            height = minOf(TopMappedRootChildBandHeight, frame.height),
        )
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
            }
        }
        provenance.source?.let { appendPicture("source", it) }
        provenance.mask?.let { appendPicture("mask", it) }
        provenance.destination?.let { appendPicture("destination", it) }
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
        }
    }

    private fun pixelHex(pixel: Int): String = "0x${pixel.toUInt().toString(16).padStart(8, '0')}"
}
