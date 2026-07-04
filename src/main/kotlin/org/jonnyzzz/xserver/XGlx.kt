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
    const val Texture2DExt = 0x20DC
    const val TextureRectangleExt = 0x20DD
    const val OptimalPbufferWidthSgix = 0x8019
    const val OptimalPbufferHeightSgix = 0x801A

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

    fun serverString(name: Int): String =
        when (name) {
            VendorName -> "jonnyzzz/x"
            VersionName -> "$MajorVersion.$MinorVersion"
            ExtensionsName -> Extensions
            else -> ""
        }

    fun visualConfig(): IntArray =
        intArrayOf(
            X11Ids.RootVisual,
            XVisualClassTrueColor,
            1,
            8,
            8,
            8,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            24,
            24,
            0,
            0,
            0,
            0x20,
            0x8000,
            0x23,
            0x8000,
            0x25,
            0,
            0x26,
            0,
            0x27,
            0,
            0x28,
            0,
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

    fun fbConfig(): IntArray {
        val pairs = listOf(
            VisualIdExt to X11Ids.RootVisual,
            FbConfigId to RootFbConfigId,
            0x8012 to 1,
            4 to 1,
            RenderType to RgbaBit,
            5 to 0,
            6 to 0,
            2 to 32,
            3 to 0,
            7 to 0,
            8 to 8,
            9 to 8,
            10 to 8,
            11 to 8,
            14 to 0,
            15 to 0,
            16 to 0,
            17 to 0,
            12 to 0,
            13 to 8,
            XVisualType to TrueColor,
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
            DrawableType to (WindowBit or PixmapBit or PbufferBit),
            BindToTextureRgbExt to 1,
            BindToTextureRgbaExt to 1,
            BindToMipmapTextureExt to 0,
            BindToTextureTargetsExt to (Texture2DBitExt or TextureRectangleBitExt),
            YInvertedExt to DontCare,
            MaxPbufferWidth to 4096,
            MaxPbufferHeight to 4096,
            MaxPbufferPixels to 4096 * 4096,
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

    private const val XVisualClassTrueColor = 4
}
