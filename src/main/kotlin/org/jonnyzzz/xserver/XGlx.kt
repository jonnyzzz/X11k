package org.jonnyzzz.xserver

internal object XGlx {
    const val MajorOpcode = 128
    const val FirstEvent = 0
    const val FirstError = 128
    const val BadContext = FirstError
    const val BadDrawable = FirstError + 2
    const val BadPixmap = FirstError + 3
    const val BadContextTag = FirstError + 4
    const val BadRenderRequest = FirstError + 6
    const val BadLargeRequest = FirstError + 7
    const val BadUnsupportedPrivateRequest = FirstError + 8
    const val BadFBConfig = FirstError + 9
    const val BadPbuffer = FirstError + 10
    const val BadWindow = FirstError + 12
    const val MajorVersion = 1
    const val MinorVersion = 4
    const val Extensions = "GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile GLX_EXT_create_context_es2_profile"

    const val Render = 1
    const val RenderLarge = 2
    const val CreateContext = 3
    const val DestroyContext = 4
    const val MakeCurrent = 5
    const val QueryVersion = 7
    const val IsDirect = 6
    const val WaitGL = 8
    const val WaitX = 9
    const val CopyContext = 10
    const val SwapBuffers = 11
    const val UseXFont = 12
    const val GetError = 115
    const val GetFloatv = 116
    const val GetIntegerv = 117
    const val GetString = 129
    const val CreateGLXPixmap = 13
    const val GetVisualConfigs = 14
    const val DestroyGLXPixmap = 15
    const val VendorPrivate = 16
    const val VendorPrivateWithReply = 17
    const val QueryExtensionsString = 18
    const val QueryServerString = 19
    const val ClientInfo = 20
    const val GetFBConfigs = 21
    const val CreatePixmap = 22
    const val DestroyPixmap = 23
    const val CreateNewContext = 24
    const val QueryContext = 25
    const val MakeContextCurrent = 26
    const val CreatePbuffer = 27
    const val DestroyPbuffer = 28
    const val GetDrawableAttributes = 29
    const val ChangeDrawableAttributes = 30
    const val CreateWindow = 31
    const val DestroyWindow = 32
    const val SetClientInfoARB = 33
    const val CreateContextAttribsARB = 34
    const val SetClientInfo2ARB = 35

    const val VendorName = 1
    const val VersionName = 2
    const val ExtensionsName = 3

    const val RgbaType = 0x8014
    const val RgbaBit = 0x00000001
    const val RootFbConfigId = X11Ids.RootVisual
    const val ShareContextExt = 0x800A
    const val VisualIdExt = 0x800B
    const val ScreenExt = 0x800C
    const val DrawableType = 0x8010
    const val RenderType = 0x8011
    const val FbConfigId = 0x8013
    const val MaxPbufferWidth = 0x8016
    const val MaxPbufferHeight = 0x8017
    const val MaxPbufferPixels = 0x8018
    const val Width = 0x801D
    const val Height = 0x801E
    const val EventMask = 0x801F
    const val ContextMajorVersionArb = 0x2091
    const val ContextMinorVersionArb = 0x2092
    const val ContextProfileMaskArb = 0x9126
    const val ContextCoreProfileBitArb = 0x00000001
    const val ContextCompatibilityProfileBitArb = 0x00000002
    const val ContextEs2ProfileBitExt = 0x00000004
    const val WindowBit = 0x00000001
    const val PixmapBit = 0x00000002
    const val PbufferBit = 0x00000004
    const val PreservedContents = 0x801B
    const val LargestPbuffer = 0x801C
    const val PbufferHeight = 0x8040
    const val PbufferWidth = 0x8041
    const val ConfigCaveat = 0x0020
    const val XVisualType = 0x0022
    const val TransparentType = 0x0023
    const val TransparentIndexValue = 0x0024
    const val TransparentRedValue = 0x0025
    const val TransparentGreenValue = 0x0026
    const val TransparentBlueValue = 0x0027
    const val TransparentAlphaValue = 0x0028
    const val TrueColor = 0x8002
    const val None = 0x8000
    const val DontCare = -1
    const val SwapMethodOml = 0x8060
    const val SwapUndefinedOml = 0x8063
    const val VisualSelectGroupSgix = 0x8028
    const val BindToTextureRgbExt = 0x20D0
    const val BindToTextureRgbaExt = 0x20D1
    const val BindToMipmapTextureExt = 0x20D2
    const val BindToTextureTargetsExt = 0x20D3
    const val YInvertedExt = 0x20D4
    const val TextureTargetExt = 0x20D6
    const val Texture1DBitExt = 0x00000001
    const val Texture2DBitExt = 0x00000002
    const val TextureRectangleBitExt = 0x00000004
    const val TextureTargetsExt = Texture1DBitExt or Texture2DBitExt or TextureRectangleBitExt
    const val Texture2DExt = 0x20DC
    const val TextureRectangleExt = 0x20DD
    const val OptimalPbufferWidthSgix = 0x8019
    const val OptimalPbufferHeightSgix = 0x801A
    const val GlVendor = 0x1F00
    const val GlRenderer = 0x1F01
    const val GlVersion = 0x1F02
    const val GlExtensions = 0x1F03
    const val GlShadingLanguageVersion = 0x8B8C
    const val GlVendorString = "Mesa/X.org"
    const val GlRendererString = "llvmpipe (jonnyzzz/x)"
    const val GlVersionString = "2.1 Mesa 26.0.0"
    const val GlShadingLanguageVersionString = "1.20"
    const val GlExtensionsString = ""
    val GlEsExtensions = listOf(
        "GL_EXT_discard_framebuffer",
        "GL_EXT_read_format_bgra",
        "GL_EXT_texture_format_BGRA8888",
        "GL_OES_depth24",
        "GL_OES_element_index_uint",
        "GL_OES_packed_depth_stencil",
        "GL_OES_rgb8_rgba8",
        "GL_OES_standard_derivatives",
        "GL_OES_texture_npot",
    )
    val GlEsExtensionsString = GlEsExtensions.joinToString(" ")
    val GlEsExtensionCount: Int get() = GlEsExtensions.size
    const val GlModelview = 0x1700
    const val GlActiveTexture0 = 0x84C0
    const val GlPointSize = 0x0B11
    const val GlAliasedPointSizeRange = 0x846D
    const val GlSmoothPointSizeRange = 0x0B12
    const val GlLineWidth = 0x0B21
    const val GlAliasedLineWidthRange = 0x846E
    const val GlSmoothLineWidthRange = 0x0B22
    const val GlDepthRange = 0x0B70
    const val GlMatrixMode = 0x0BA0
    const val GlViewport = 0x0BA2
    const val GlDepthBits = 0x0D56
    const val GlRedBits = 0x0D52
    const val GlGreenBits = 0x0D53
    const val GlBlueBits = 0x0D54
    const val GlAlphaBits = 0x0D55
    const val GlStencilBits = 0x0D57
    const val GlDoublebuffer = 0x0C32
    const val GlStereo = 0x0C33
    const val GlPackAlignment = 0x0D05
    const val GlUnpackAlignment = 0x0CF5
    const val GlMaxLights = 0x0D31
    const val GlMaxTextureSize = 0x0D33
    const val GlMaxModelviewStackDepth = 0x0D36
    const val GlMaxProjectionStackDepth = 0x0D38
    const val GlMaxTextureStackDepth = 0x0D39
    const val GlMaxViewportDims = 0x0D3A
    const val GlMax3DTextureSize = 0x8073
    const val GlMaxElementsVertices = 0x80E8
    const val GlMaxElementsIndices = 0x80E9
    const val GlActiveTexture = 0x84E0
    const val GlMaxTextureUnits = 0x84E2
    const val GlMaxRenderbufferSize = 0x84E8
    const val GlMaxCubeMapTextureSize = 0x851C
    const val GlMaxTextureImageUnits = 0x8872
    const val GlMaxDrawBuffers = 0x8824
    const val GlMaxVertexTextureImageUnits = 0x8B4C
    const val GlMaxCombinedTextureImageUnits = 0x8B4D
    const val GlCurrentProgram = 0x8B8D
    const val GlMajorVersion = 0x821B
    const val GlMinorVersion = 0x821C
    const val GlNumExtensions = 0x821D
    const val GlSampleBuffers = 0x80A8
    const val GlSamples = 0x80A9
    const val GlMaxTextureMaxAnisotropyExt = 0x84FF

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            Render -> "Render"
            RenderLarge -> "RenderLarge"
            CreateContext -> "CreateContext"
            DestroyContext -> "DestroyContext"
            MakeCurrent -> "MakeCurrent"
            IsDirect -> "IsDirect"
            QueryVersion -> "QueryVersion"
            WaitGL -> "WaitGL"
            WaitX -> "WaitX"
            CopyContext -> "CopyContext"
            SwapBuffers -> "SwapBuffers"
            UseXFont -> "UseXFont"
            GetError -> "GetError"
            GetFloatv -> "GetFloatv"
            GetIntegerv -> "GetIntegerv"
            GetString -> "GetString"
            CreateGLXPixmap -> "CreateGLXPixmap"
            GetVisualConfigs -> "GetVisualConfigs"
            DestroyGLXPixmap -> "DestroyGLXPixmap"
            VendorPrivate -> "VendorPrivate"
            VendorPrivateWithReply -> "VendorPrivateWithReply"
            QueryExtensionsString -> "QueryExtensionsString"
            QueryServerString -> "QueryServerString"
            ClientInfo -> "ClientInfo"
            GetFBConfigs -> "GetFBConfigs"
            CreatePixmap -> "CreatePixmap"
            DestroyPixmap -> "DestroyPixmap"
            CreateNewContext -> "CreateNewContext"
            QueryContext -> "QueryContext"
            MakeContextCurrent -> "MakeContextCurrent"
            CreatePbuffer -> "CreatePbuffer"
            DestroyPbuffer -> "DestroyPbuffer"
            GetDrawableAttributes -> "GetDrawableAttributes"
            ChangeDrawableAttributes -> "ChangeDrawableAttributes"
            CreateWindow -> "CreateWindow"
            DestroyWindow -> "DestroyWindow"
            SetClientInfoARB -> "SetClientInfoARB"
            CreateContextAttribsARB -> "CreateContextAttribsARB"
            SetClientInfo2ARB -> "SetClientInfo2ARB"
            else -> "Unknown"
        }

    fun glString(name: Int, context: XGlxContext? = null): String =
        when (name) {
            GlVendor -> GlVendorString
            GlRenderer -> GlRendererString
            GlVersion -> glVersionString(context)
            GlExtensions -> glExtensionsString(context)
            GlShadingLanguageVersion -> glShadingLanguageVersionString(context)
            else -> ""
        }

    fun serverString(name: Int): String =
        when (name) {
            VendorName -> "jonnyzzz/x"
            VersionName -> "$MajorVersion.$MinorVersion"
            ExtensionsName -> Extensions
            else -> ""
        }

    fun glIntegerValues(pname: Int, screenWidth: Int, screenHeight: Int, context: XGlxContext? = null): IntArray =
        when (pname) {
            GlMatrixMode -> intArrayOf(GlModelview)
            GlViewport -> intArrayOf(0, 0, screenWidth, screenHeight)
            GlDepthBits -> intArrayOf(24)
            GlRedBits, GlGreenBits, GlBlueBits, GlAlphaBits -> intArrayOf(8)
            GlStencilBits -> intArrayOf(8)
            GlDoublebuffer, GlStereo, GlSampleBuffers, GlSamples, GlCurrentProgram -> intArrayOf(0)
            GlNumExtensions -> intArrayOf(glExtensionCount(context))
            GlPackAlignment, GlUnpackAlignment -> intArrayOf(4)
            GlMaxLights -> intArrayOf(8)
            GlMaxTextureSize, GlMaxRenderbufferSize, GlMaxCubeMapTextureSize -> intArrayOf(4096)
            GlMax3DTextureSize -> intArrayOf(2048)
            GlMaxElementsVertices, GlMaxElementsIndices -> intArrayOf(1_048_576)
            GlMaxModelviewStackDepth -> intArrayOf(32)
            GlMaxProjectionStackDepth, GlMaxTextureStackDepth -> intArrayOf(2)
            GlMaxViewportDims -> intArrayOf(screenWidth, screenHeight)
            GlActiveTexture -> intArrayOf(GlActiveTexture0)
            GlMaxTextureUnits, GlMaxTextureImageUnits -> intArrayOf(8)
            GlMaxVertexTextureImageUnits -> intArrayOf(0)
            GlMaxCombinedTextureImageUnits -> intArrayOf(8)
            GlMaxDrawBuffers -> intArrayOf(1)
            GlMajorVersion -> intArrayOf(glMajorVersion(context))
            GlMinorVersion -> intArrayOf(glMinorVersion(context))
            else -> intArrayOf(0)
        }

    private fun glVersionString(context: XGlxContext?): String {
        val prefix = if (context.isEsProfile()) "OpenGL ES " else ""
        val major = glMajorVersion(context)
        val minor = glMinorVersion(context)
        return "$prefix$major.$minor Mesa 26.0.0"
    }

    private fun glShadingLanguageVersionString(context: XGlxContext?): String =
        if (context.isEsProfile()) "OpenGL ES GLSL ES 1.00" else GlShadingLanguageVersionString

    private fun glExtensionsString(context: XGlxContext?): String =
        if (context.isEsProfile()) GlEsExtensionsString else GlExtensionsString

    private fun glExtensionCount(context: XGlxContext?): Int =
        if (context.isEsProfile()) GlEsExtensionCount else 0

    private fun glMajorVersion(context: XGlxContext?): Int =
        context?.contextMajorVersion?.takeIf { it > 0 } ?: 2

    private fun glMinorVersion(context: XGlxContext?): Int =
        context?.contextMinorVersion?.takeIf { it >= 0 } ?: if (context == null || !context.hasRequestedVersion()) 1 else 0

    private fun XGlxContext?.isEsProfile(): Boolean =
        this != null && (profileMask and ContextEs2ProfileBitExt) != 0

    private fun XGlxContext.hasRequestedVersion(): Boolean =
        contextMajorVersion != null || contextMinorVersion != null

    fun glFloatValues(pname: Int): FloatArray =
        when (pname) {
            GlPointSize, GlLineWidth, GlMaxTextureMaxAnisotropyExt -> floatArrayOf(1.0f)
            GlAliasedPointSizeRange, GlSmoothPointSizeRange, GlAliasedLineWidthRange, GlSmoothLineWidthRange -> floatArrayOf(1.0f, 1.0f)
            GlDepthRange -> floatArrayOf(0.0f, 1.0f)
            else -> floatArrayOf(0.0f)
        }

    fun visualConfig(): IntArray =
        intArrayOf(
            X11Ids.RootVisual,
            XVisualClassTrueColor,
            1,
            8,
            8,
            8,
            8,
            0,
            0,
            0,
            0,
            1,
            0,
            32,
            24,
            8,
            0,
            0,
            0x20,
            0x8000,
            0x23,
            0x8000,
            0x25,
            DontCare,
            0x26,
            DontCare,
            0x27,
            DontCare,
            0x28,
            DontCare,
            0x24,
            0,
            100001,
            0,
            100000,
            0,
            0x8028,
            0,
            0,
            0,
        )

    fun fbConfig(): IntArray = fbConfigs().first()

    fun fbConfigs(): List<IntArray> =
        FbConfigs.map { spec -> fbConfig(spec) }

    fun isKnownFbConfig(id: Int): Boolean =
        FbConfigs.any { it.id == id }

    fun fbConfigSupports(id: Int, drawableType: Int): Boolean =
        FbConfigs.firstOrNull { it.id == id }?.let { spec -> spec.drawableType and drawableType != 0 } == true

    fun visualIdForFbConfig(id: Int): Int =
        FbConfigs.firstOrNull { it.id == id }?.visualId ?: 0

    private fun fbConfig(spec: FbConfigSpec): IntArray {
        val pairs = listOf(
            VisualIdExt to spec.visualId,
            FbConfigId to spec.id,
            0x8012 to 1,
            4 to 1,
            RenderType to RgbaBit,
            5 to spec.doubleBuffer,
            6 to 0,
            2 to spec.bufferSize,
            3 to 0,
            7 to 0,
            8 to 8,
            9 to 8,
            10 to 8,
            11 to spec.alphaSize,
            14 to 0,
            15 to 0,
            16 to 0,
            17 to 0,
            12 to spec.depthSize,
            13 to spec.stencilSize,
            XVisualType to if (spec.visualId == 0) None else TrueColor,
            ConfigCaveat to None,
            TransparentType to None,
            TransparentRedValue to DontCare,
            TransparentGreenValue to DontCare,
            TransparentBlueValue to DontCare,
            TransparentAlphaValue to DontCare,
            TransparentIndexValue to 0,
            SwapMethodOml to SwapUndefinedOml,
            100001 to 0,
            100000 to 0,
            VisualSelectGroupSgix to 0,
            DrawableType to spec.drawableType,
            BindToTextureRgbExt to 1,
            BindToTextureRgbaExt to if (spec.alphaSize > 0) 1 else 0,
            BindToMipmapTextureExt to 0,
            BindToTextureTargetsExt to TextureTargetsExt,
            YInvertedExt to DontCare,
            MaxPbufferWidth to 0,
            MaxPbufferHeight to 0,
            MaxPbufferPixels to 0,
            OptimalPbufferWidthSgix to 0,
            OptimalPbufferHeightSgix to 0,
            0 to 0,
        )
        check(pairs.size == FbConfigAttributePairs) {
            "GLX FBConfig must contain $FbConfigAttributePairs pairs, got ${pairs.size}"
        }
        val values = IntArray(FbConfigAttributePairs * 2)
        var offset = 0
        for ((attribute, value) in pairs) {
            values[offset++] = attribute
            values[offset++] = value
        }
        return values
    }

    const val VisualConfigValues = 40
    const val FbConfigAttributePairs = 44

    private val FbConfigs = listOf(
        FbConfigSpec(
            id = RootFbConfigId,
            visualId = X11Ids.RootVisual,
            doubleBuffer = 1,
            depthSize = 24,
            stencilSize = 8,
        ),
        FbConfigSpec(
            id = RootFbConfigId + 5,
            visualId = X11Ids.RootVisual,
            doubleBuffer = 0,
            depthSize = 0,
            stencilSize = 0,
        ),
        FbConfigSpec(
            id = RootFbConfigId + 1,
            visualId = 0,
            doubleBuffer = 0,
            depthSize = 0,
            stencilSize = 0,
            drawableType = PixmapBit or PbufferBit,
        ),
        FbConfigSpec(
            id = RootFbConfigId + 2,
            visualId = 0,
            doubleBuffer = 1,
            depthSize = 0,
            stencilSize = 0,
            drawableType = PixmapBit or PbufferBit,
        ),
        FbConfigSpec(
            id = RootFbConfigId + 3,
            visualId = 0,
            doubleBuffer = 0,
            depthSize = 24,
            stencilSize = 8,
            drawableType = PixmapBit or PbufferBit,
        ),
        FbConfigSpec(
            id = RootFbConfigId + 4,
            visualId = 0,
            doubleBuffer = 1,
            depthSize = 24,
            stencilSize = 8,
            drawableType = PixmapBit or PbufferBit,
        ),
    )

    private data class FbConfigSpec(
        val id: Int,
        val visualId: Int,
        val doubleBuffer: Int,
        val depthSize: Int,
        val stencilSize: Int,
        val alphaSize: Int = 8,
        val bufferSize: Int = 32,
        val drawableType: Int = WindowBit or PixmapBit or PbufferBit,
    )

    private const val XVisualClassTrueColor = 4
}
