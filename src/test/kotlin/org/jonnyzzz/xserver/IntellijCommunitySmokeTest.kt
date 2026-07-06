package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.utility.DockerImageName
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.awt.image.BufferedImage
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.net.ServerSocket
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntellijCommunitySmokeTest {
    @Test
    fun `intellij smoke svg png parser is attribute-order independent`() {
        val svg =
            """
            <svg>
              <image data-window-id="0x20" x="12" y="24" width="10" height="10" href="data:image/png;base64,aGVsbG8="/>
              <g class="semantic-window-layers" visibility = 'hidden'>
                <image data-window-id="0x22" x="1" y="1" width="2" height="2" href="data:image/png;base64,aGlkZGVu"/>
              </g>
              <image href="data:image/png;base64,d29ybGQ=" height="20" width="20" y="48" x="36" data-window-id="0x21"/>
            </svg>
            """.trimIndent()

        val images = pngDataUris(svg)

        assertEquals(listOf("0x20", "0x21"), images.map { it.id })
        assertEquals("hello", images[0].bytes.decodeToString())
        assertEquals("world", images[1].bytes.decodeToString())
        assertEquals(listOf(12, 36), images.map { it.x })
        assertEquals(listOf(24, 48), images.map { it.y })
        assertEquals(listOf(10, 20), images.map { it.width })
        assertEquals(listOf(10, 20), images.map { it.height })
    }

    @Test
    fun `intellij smoke svg composition parser keeps borders and clip paths`() {
        val svg =
            """
            <svg>
              <defs>
                <clipPath id="clip-screen-20"><rect x="1" y="2" width="3" height="4"/></clipPath>
              </defs>
              <image data-window-id="0x20" clip-path="url(#clip-screen-20)" x="12" y="24" width="10" height="10" href="data:image/png;base64,aGVsbG8="/>
              <rect class="window-border" data-border-window-id="0x21" clip-path="url(#clip-screen-20)" x="7" y="8" width="9" height="10" fill="#112233"/>
              <g class="semantic-window-layers" visibility="hidden">
                <g>
                  <image data-window-id="0x22" x="1" y="1" width="2" height="2" href="data:image/png;base64,aGlkZGVu"/>
                </g>
                <g>
                  <rect class="window-border" data-border-window-id="0x23" x="3" y="3" width="4" height="4" fill="#445566"/>
                </g>
              </g>
            </svg>
            """.trimIndent()

        val layers = svgCompositionLayers(svg)

        assertEquals(listOf("0x20", "0x21"), layers.map { it.id })
        assertEquals("hello", layers[0].bytes?.decodeToString())
        assertEquals(listOf(Rectangle(1, 2, 3, 4)), layers[0].clipRectangles)
        assertEquals(null, layers[0].fill)
        assertEquals(null, layers[1].bytes)
        assertEquals(0xff11_2233.toInt(), layers[1].fill)
        assertEquals(listOf(Rectangle(1, 2, 3, 4)), layers[1].clipRectangles)
        assertEquals(7, layers[1].x)
        assertEquals(8, layers[1].y)
        assertEquals(9, layers[1].width)
        assertEquals(10, layers[1].height)
    }

    @Test
    fun `intellij smoke svg composition parser prefers composited root framebuffer`() {
        val svg =
            """
            <svg>
              <image class="framebuffer-image screen-framebuffer-image" data-source="composited-root" data-window-id="0x26" x="0" y="0" width="4" height="3" href="data:image/png;base64,cm9vdA=="/>
              <g class="semantic-window-layers" visibility="hidden">
                <image class="framebuffer-image" data-source="window-framebuffer" data-window-id="0x20" x="1" y="2" width="3" height="4" href="data:image/png;base64,aGlkZGVu"/>
              </g>
            </svg>
            """.trimIndent()

        val layers = svgCompositionLayers(svg)

        assertEquals(1, layers.size)
        assertEquals("0x26", layers.single().id)
        assertEquals("composited-root", layers.single().source)
        assertEquals("root", layers.single().bytes?.decodeToString())
        assertEquals(0, layers.single().x)
        assertEquals(0, layers.single().y)
        assertEquals(4, layers.single().width)
        assertEquals(3, layers.single().height)
    }

    @Test
    fun `intellij smoke svg selection picks frame closest to robot capture`() {
        val robotImage = solidImage(6, 6, 0xff33_6699.toInt())
        val farImage = solidImage(6, 6, 0xff33_6699.toInt()).also { image ->
            image.setRGB(1, 1, 0xff99_6633.toInt())
        }
        val farSvg = svgWithRootImage(farImage)
        val closeSvg = svgWithRootImage(robotImage)

        val selected = closestIntellijSvgToRobot(
            robot = visualCapture(robotImage),
            candidates = listOf(farSvg, closeSvg),
            width = 6,
            height = 6,
        )

        assertEquals(closeSvg, selected)
    }

    @Test
    fun `intellij html preview parser identifies large retained surfaces`() {
        val html =
            """
            <section class="window-contents">
              <article class="preview">
                <header><strong>Content window</strong> <span>0x200004</span> 1260x860 focused</header>
                <svg class="window-preview-svg" viewBox="0 0 1260 860" aria-label="Content window">
                  <image class="framebuffer-image backing-pixmap-image" data-window-id="0x200004" data-pixmap-id="0x300001" data-picture-id="0x300002" data-source="retained-picture" x="0" y="0" width="1260" height="860" href="data:image/png;base64,aGVsbG8="/>
                </svg>
              </article>
            </section>
            """.trimIndent()

        val previews = htmlWindowPreviewSurfaces(html)

        assertEquals(1, previews.size)
        assertEquals("Content window", previews.single().label)
        assertEquals(1260, previews.single().viewWidth)
        assertEquals(860, previews.single().viewHeight)
        assertEquals("retained-picture", previews.single().source)
        assertEquals("0x200004", previews.single().windowId)
        assertEquals("0x300001", previews.single().pixmapId)
        assertEquals("0x300002", previews.single().pictureId)
        assertIntellijHtmlPreviewHasLargeSurface(html)
        assertIntellijHtmlPreviewHasLargeSurface(html, """- 0x200004 parent=0x1 label="Content window" geometry=0,0 1260x860 mapped=true""")
    }

    @Test
    fun `intellij visual region metrics identify frame and margins`() {
        val text =
            """
            Screen:
            - size=1280x900

            Window hierarchy and geometry:
            - 0x26 parent=0x0 label="root" geometry=0,0 1280x900 class=InputOutput depth=24 visual=0x28 backgroundPixel=16777215 backgroundPixmap=none borderPixel=0 borderPixmap=none mapped=true focused=false stack=0
            - 0x200003 parent=0x26 label="Idea frame" geometry=10,20 1260x860 class=InputOutput depth=24 visual=0x28 backgroundPixel=0 backgroundPixmap=none borderPixel=0 borderPixmap=none mapped=true focused=false stack=1
            - 0x200004 parent=0x200003 label="content" geometry=0,0 1260x860 class=InputOutput depth=24 visual=0x28 backgroundPixel=0 backgroundPixmap=none borderPixel=0 borderPixmap=none mapped=true focused=true stack=2
            """.trimIndent()
        val expected = BufferedImage(1280, 900, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.dispose()
        }
        val actualRobot = BufferedImage(1280, 900, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(10, 20, 1260, 860)
            graphics.color = java.awt.Color.GREEN
            graphics.fillRect(15, 25, 2, 3)
            graphics.dispose()
        }
        val actualSvg = BufferedImage(1280, 900, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.dispose()
        }

        val metrics = intellijVisualRegionMetrics(
            text = text,
            expected = visualCapture(expected),
            actualRobot = visualCapture(actualRobot),
            actualSvg = visualCapture(actualSvg),
        )

        assertTrue(metrics.contains("ideaFrame=10,20 1260x860"), metrics)
        assertTrue(metrics.contains("ideaFrameArea=1083600"), metrics)
        assertTrue(metrics.contains("topMargin=20"), metrics)
        assertTrue(metrics.contains("leftMargin=10"), metrics)
        assertTrue(metrics.contains("rightMargin=10"), metrics)
        assertTrue(metrics.contains("bottomMargin=20"), metrics)
        assertTrue(metrics.contains("robotInsideFrameCoverageRatio=1.0"), metrics)
        assertTrue(metrics.contains("robotOutsideFrameNonWhitePixels=0"), metrics)
        assertTrue(metrics.contains("svgInsideFrameCoverageRatio=1.0"), metrics)
        assertTrue(metrics.contains("robotMismatchBounds=0,0 1280x900"), metrics)
        assertTrue(metrics.contains("robotInsideFrameMismatchBounds=5,5 2x3"), metrics)
        assertTrue(metrics.contains("svgMismatchBounds=none"), metrics)
        assertTrue(metrics.contains("svgInsideFrameMismatchBounds=none"), metrics)
        assertTrue(metrics.contains("robotVsSvgSampledDistance="), metrics)
        assertTrue(metrics.contains("robotVsSvgMismatchBounds=0,0 1280x900"), metrics)
        assertTrue(metrics.contains("robotVsSvgInsideFrameMismatchBounds=5,5 2x3"), metrics)
        assertTrue(metrics.contains("topFrameBand=10,20 1260x120"), metrics)
        assertTrue(metrics.contains("robotTopFrameBandMismatchBounds=5,5 2x3"), metrics)
        assertTrue(metrics.contains("rightFrameBand=1174,20 96x860"), metrics)
        assertTrue(metrics.contains("robotRightFrameBandMismatchBounds=none"), metrics)
        assertTrue(metrics.contains("bottomFrameBand=10,784 1260x96"), metrics)
        assertTrue(metrics.contains("robotBottomFrameBandMismatchBounds=none"), metrics)
    }

    @Test
    fun `intellij render band diagnostics split top frame section from text report`() {
        val text =
            """
            RENDER operations:
            - #42 Composite minor=8

            RENDER operations intersecting top mapped root-child band:
            - region=10,20 1260x120 window=0x200003
            - #41 Composite minor=8 root=10,20 65x2 local=0,0 65x2 sourcePopulation=0x400120#1 paints=1 first=#40/Composite last=#40/Composite

            RENDER operations intersecting right mapped root-child band:
            - region=1174,20 96x860 window=0x200003
            - None.

            RENDER operations intersecting bottom mapped root-child band:
            - region=10,784 1260x96 window=0x200003
            - None.

            Recent PutImage commands:
            - None.
            """.trimIndent()

        val top = intellijRenderBandSection(text, "top")
        val right = intellijRenderBandSection(text, "right")
        val missing = intellijRenderBandSection(text, "left")

        assertTrue(top.startsWith("RENDER operations intersecting top mapped root-child band:"), top)
        assertTrue(top.contains("sourcePopulation=0x400120#1"), top)
        assertFalse(top.contains("right mapped root-child band"), top)
        assertTrue(right.contains("region=1174,20 96x860"), right)
        assertEquals("", missing)
    }

    @Test
    fun `intellij parity readiness waits for post markdown indexing completion`() {
        val baseLog =
            """
            2026-07-04 19:56:49,053 [   1721]   INFO - #c.i.i.p.i.ProjectViewInitNotifier - Project View initialization completed
            2026-07-04 19:56:50,042 [   2710]   INFO - #c.j.p.c.BeforeFileOpenLoggerListener - fileOpened README.md
            """.trimIndent()
        val indexedBeforeMarkdown =
            """
            $baseLog
            2026-07-04 19:56:50,100 [   2768]   INFO - #c.i.u.i.UnindexedFilesIndexer - Finished for jonnyzzz-x. Unindexed files update took 100ms; general responsiveness: ok; EDT responsiveness: ok
            2026-07-04 19:56:50,190 [   2858]   INFO - #org.intellij.plugins.markdown.ui.preview.MarkdownPreviewFileEditor${'$'}Companion - MarkdownPreviewFileEditor: setHtml finished
            """.trimIndent()
        val readyLog =
            """
            $indexedBeforeMarkdown
            2026-07-04 19:56:51,744 [   4412]   INFO - #c.i.u.i.UnindexedFilesIndexer - Finished for jonnyzzz-x. Unindexed files update took 1535ms; general responsiveness: ok; EDT responsiveness: ok
            """.trimIndent()

        val missingMarkdown = intellijParityReadiness(baseLog)
        assertFalse(missingMarkdown.ready)
        assertEquals(listOf("markdown-preview"), missingMarkdown.missing)

        val missingPostMarkdownIndexing = intellijParityReadiness(indexedBeforeMarkdown)
        assertFalse(missingPostMarkdownIndexing.ready)
        assertEquals(listOf("post-markdown-indexing"), missingPostMarkdownIndexing.missing)

        val ready = intellijParityReadiness(readyLog)
        assertTrue(ready.ready)
        assertEquals(emptyList(), ready.missing)
    }

    @Test
    fun `intellij clean project export contains tracked files only`() {
        val root = projectRoot()
        val untracked = root.resolve("build/tmp/intellij-community-smoke/untracked-sentinel.txt")
        Files.createDirectories(untracked.parent)
        Files.writeString(untracked, "this must not be mounted into IntelliJ")
        try {
            val export = cleanProjectExport()

            assertTrue(Files.exists(export.resolve("README.md")), "tracked files should be exported")
            assertFalse(
                Files.exists(export.resolve("build/tmp/intellij-community-smoke/untracked-sentinel.txt")),
                "generated and untracked files should not be exported into the IntelliJ project mount",
            )
        } finally {
            Files.deleteIfExists(untracked)
        }
    }

    @Test
    fun `intellij heavyweight cache directory stays under build tmp`() {
        val root = projectRoot()
        val cache = intellijCacheDir()

        assertTrue(cache.startsWith(root.resolve("build/tmp").normalize()), "Unexpected IntelliJ cache path: $cache")
    }

    @Test
    fun `intellij log artifact names preserve pid suffixes`() {
        assertEquals("intellij-kotlin-jcef.log", intellijLogArtifactName("intellij-kotlin", "/tmp/idea-log/jcef.log"))
        assertEquals("intellij-kotlin-jcef-55.log", intellijLogArtifactName("intellij-kotlin", "/tmp/idea-log/jcef_55.log"))
        assertEquals(
            "intellij-kotlin-jcef-chromium-55.log",
            intellijLogArtifactName("intellij-kotlin", "/tmp/idea-log/jcef_chromium_55.log"),
        )
    }

    @Test
    fun `intellij ui diagnostics summarize environment and header decisions`() {
        val logs = listOf(
            IntellijLogArtifact(
                fileName = "intellij-xvfb-run-intellij-env.log",
                text =
                    """
                    DISPLAY=:99
                    XDG_CURRENT_DESKTOP=<unset>
                    XDG_SESSION_TYPE=<unset>
                    DESKTOP_SESSION=<unset>
                    AWT_TOOLKIT=<unset>
                    _JAVA_AWT_WM_NONREPARENTING=1
                    IDEA_REMOTE_X11_WORKAROUND=false
                    IDEA_X11_DEBUG=true
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-run-intellij-env.log",
                text =
                    """
                    DISPLAY=host.docker.internal:231
                    XDG_CURRENT_DESKTOP=<unset>
                    XDG_SESSION_TYPE=<unset>
                    DESKTOP_SESSION=<unset>
                    AWT_TOOLKIT=<unset>
                    _JAVA_AWT_WM_NONREPARENTING=1
                    IDEA_REMOTE_X11_WORKAROUND=false
                    IDEA_X11_DEBUG=true
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-xvfb-ui-lnf.xml",
                text = """<application><component name="UISettings"><option name="mainMenuDisplayMode" value="SEPARATE_TOOLBAR" /><option name="showMainMenu" value="true" /></component></application>""",
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-ui-lnf.xml",
                text = """<application><component name="UISettings"><option value="SEPARATE_TOOLBAR" name="mainMenuDisplayMode" /><option value="true" name="showMainMenu" /></component></application>""",
            ),
            IntellijLogArtifact(
                fileName = "intellij-xvfb-extensions-xdpyinfo.log",
                text =
                    """
                    number of extensions:    2
                        GLX  (opcode: 150, base event: 95, base error: 158)
                        XInputExtension  (opcode: 131, base event: 66, base error: 129)
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-extensions-xdpyinfo.log",
                text =
                    """
                    number of extensions:    2
                        GLX
                        XInputExtension
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-run.log",
                text =
                    """
                    2026-07-06 INFO - #com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomWindowHeaderUtil - native Linux title is not supported because _NET protocol is not supported
                    2026-07-06 INFO - #com.intellij.ide.ui.MainMenuDisplayMode - mainMenuDisplayMode=SEPARATE_TOOLBAR
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-xvfb-ui-runtime-diagnostics.log",
                text =
                    """
                    agentLoaded=true
                    runtimeMainMenuDisplayMode=Merge with Main Toolbar
                    runtimeStateMainMenuDisplayMode=MERGED_WITH_MAIN_TOOLBAR
                    runtimeShadowStateMainMenuDisplayMode=MERGED_WITH_MAIN_TOOLBAR
                    runtimeMainMenuDisplayModePrev=Hide under Hamburger Button
                    runtimeShowMainMenu=true
                    runtimeStateShowMainMenu=true
                    runtimeShadowStateShowMainMenu=true
                    runtimeShowMainToolbar=false
                    runtimeStateModificationCount=3
                    runtimeSettingsIdentity=101
                    runtimeStateIdentity=202
                    runtimeMenuButtonInToolbar=true
                    runtimeHideNativeLinuxTitleNotSupportedReason=INCOMPATIBLE_JBR
                    runtimeJbrWindowMoveSupported=false
                    runtimeStartupIsXToolkit=true
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-ui-runtime-diagnostics.log",
                text =
                    """
                    agentLoaded=true
                    runtimeMainMenuDisplayMode=Merge with Main Toolbar
                    runtimeStateMainMenuDisplayMode=MERGED_WITH_MAIN_TOOLBAR
                    runtimeShadowStateMainMenuDisplayMode=MERGED_WITH_MAIN_TOOLBAR
                    runtimeMainMenuDisplayModePrev=Hide under Hamburger Button
                    runtimeShowMainMenu=true
                    runtimeStateShowMainMenu=true
                    runtimeShadowStateShowMainMenu=true
                    runtimeShowMainToolbar=false
                    runtimeStateModificationCount=3
                    runtimeSettingsIdentity=303
                    runtimeStateIdentity=404
                    runtimeMenuButtonInToolbar=true
                    runtimeHideNativeLinuxTitleNotSupportedReason=INCOMPATIBLE_JBR
                    runtimeJbrWindowMoveSupported=false
                    runtimeStartupIsXToolkit=true
                    """.trimIndent(),
            ),
        )

        val summary = intellijUiDiagnosticsSummary(logs)

        assertTrue(summary.contains("xvfbDisplay=:99"), summary)
        assertTrue(summary.contains("kotlinDisplay=host.docker.internal:231"), summary)
        assertTrue(summary.contains("xvfbXdgCurrentDesktop=<unset>"), summary)
        assertTrue(summary.contains("kotlinXdgCurrentDesktop=<unset>"), summary)
        assertTrue(summary.contains("xvfbMainMenuDisplayMode=SEPARATE_TOOLBAR"), summary)
        assertTrue(summary.contains("kotlinMainMenuDisplayMode=SEPARATE_TOOLBAR"), summary)
        assertTrue(summary.contains("xvfbListsXInputExtension=true"), summary)
        assertTrue(summary.contains("kotlinListsXInputExtension=true"), summary)
        assertTrue(summary.contains("xvfbXInputWarning=false"), summary)
        assertTrue(summary.contains("kotlinXInputWarning=false"), summary)
        assertTrue(summary.contains("xvfbRuntimeAgentLoaded=true"), summary)
        assertTrue(summary.contains("kotlinRuntimeAgentLoaded=true"), summary)
        assertTrue(summary.contains("xvfbRuntimeMainMenuDisplayMode=Merge with Main Toolbar"), summary)
        assertTrue(summary.contains("kotlinRuntimeMainMenuDisplayMode=Merge with Main Toolbar"), summary)
        assertTrue(summary.contains("xvfbRuntimeStateMainMenuDisplayMode=MERGED_WITH_MAIN_TOOLBAR"), summary)
        assertTrue(summary.contains("kotlinRuntimeStateMainMenuDisplayMode=MERGED_WITH_MAIN_TOOLBAR"), summary)
        assertTrue(summary.contains("xvfbRuntimeStateModificationCount=3"), summary)
        assertTrue(summary.contains("kotlinRuntimeStateModificationCount=3"), summary)
        assertTrue(summary.contains("xvfbRuntimeMenuButtonInToolbar=true"), summary)
        assertTrue(summary.contains("kotlinRuntimeMenuButtonInToolbar=true"), summary)
        assertTrue(summary.contains("CustomWindowHeaderUtil"), summary)
        assertTrue(summary.contains("mainMenuDisplayMode=SEPARATE_TOOLBAR"), summary)
    }

    @Test
    fun `intellij runtime ui diagnostics require successful attach agent output`() {
        val logs = listOf(
            IntellijLogArtifact(
                fileName = "intellij-xvfb-ui-runtime-diagnostics.log",
                text =
                    """
                    agentLoaded=false
                    attachExit=1
                    attachOutput=com.sun.tools.attach.AttachNotSupportedException
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-ui-runtime-diagnostics.log",
                text =
                    """
                    agentLoaded=true
                    runtimeMainMenuDisplayMode=Hide under Hamburger Button
                    runtimeStateMainMenuDisplayMode=UNDER_HAMBURGER_BUTTON
                    runtimeShadowStateMainMenuDisplayMode=UNDER_HAMBURGER_BUTTON
                    runtimeMainMenuDisplayModePrev=Hide under Hamburger Button
                    runtimeShowMainMenu=true
                    runtimeStateShowMainMenu=true
                    runtimeShadowStateShowMainMenu=true
                    runtimeShowMainToolbar=false
                    runtimeStateModificationCount=0
                    runtimeSettingsIdentity=303
                    runtimeStateIdentity=404
                    runtimeMenuButtonInToolbar=true
                    runtimeHideNativeLinuxTitleNotSupportedReason=INCOMPATIBLE_JBR
                    runtimeJbrWindowMoveSupported=false
                    runtimeStartupIsXToolkit=true
                    """.trimIndent(),
            ),
        )

        assertFailsWith<AssertionError> {
            assertIntellijRuntimeUiDiagnosticsPresent(logs)
        }
    }

    @Test
    fun `intellij runtime ui diagnostics require matching visible chrome state`() {
        val logs = listOf(
            IntellijLogArtifact(
                fileName = "intellij-xvfb-ui-runtime-diagnostics.log",
                text = runtimeUiDiagnosticLog(
                    mainMenuDisplayMode = "Merge with Main Toolbar",
                    stateMainMenuDisplayMode = "MERGED_WITH_MAIN_TOOLBAR",
                    stateModificationCount = 3,
                ),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-ui-runtime-diagnostics.log",
                text = runtimeUiDiagnosticLog(
                    mainMenuDisplayMode = "Hide under Hamburger Button",
                    stateMainMenuDisplayMode = "UNDER_HAMBURGER_BUTTON",
                    stateModificationCount = 0,
                ),
            ),
        )

        val failure = assertFailsWith<AssertionError> {
            assertIntellijRuntimeUiDiagnosticsPresent(logs)
        }

        assertTrue(failure.message.orEmpty().contains("RuntimeMainMenuDisplayMode"), failure.message)
        assertTrue(failure.message.orEmpty().contains("RuntimeStateModificationCount"), failure.message)
    }

    @Test
    fun `intellij robot capture delay is configurable`() {
        val source = robotCaptureSource()

        assertTrue(source.contains("""Long.getLong("x.captureDelayMs", 1200L)"""), source)
        assertTrue(source.contains("Thread.sleep(delayMs)"), source)
    }

    @Test
    fun `intellij launcher disables project colored toolbar for deterministic parity`() {
        val source = runIntellijScriptSource()
        val harness = Files.readString(projectRoot().resolve("src/test/kotlin/org/jonnyzzz/xserver/IntellijCommunitySmokeTest.kt"))

        assertTrue(source.contains("options/ui.lnf.xml"), source)
        assertTrue(source.contains("run-intellij-env.log"), source)
        assertTrue(source.contains("XDG_CURRENT_DESKTOP"), source)
        assertTrue(source.contains("XDG_SESSION_TYPE"), source)
        assertTrue(source.contains("AWT_TOOLKIT"), source)
        assertTrue(source.contains("""<option name="differentiateProjects" value="false" />"""), source)
        assertTrue(source.contains("""<option name="mainMenuDisplayMode" value="SEPARATE_TOOLBAR" />"""), source)
        assertTrue(source.contains("""<option name="showMainMenu" value="true" />"""), source)
        assertTrue(source.contains("""<option name="useProjectColorsInMainToolbar" value="false" />"""), source)
        assertTrue(source.contains("""<option name="useSolutionColorsInMainToolbar" value="false" />"""), source)
        assertTrue(harness.contains("XIntellijUiDiagnosticsAgent"), harness)
        assertTrue(harness.contains("runtimeMenuButtonInToolbar"), harness)
        assertTrue(harness.contains("runtimeStateMainMenuDisplayMode"), harness)
        assertTrue(harness.contains("intellij-kotlin-config-options-inventory.log"), harness)
        assertTrue(harness.contains("javac --add-modules jdk.attach -d /tmp"), harness)
    }

    @Test
    fun `intellij parity uses isolated idea config for xvfb reference and kotlin run`() {
        val source = Files.readString(projectRoot().resolve("src/test/kotlin/org/jonnyzzz/xserver/IntellijCommunitySmokeTest.kt"))
        val parityAnchor = "    @Test\n    fun `intellij community robot and svg roughly match xvfb reference`()"
        val parityEnd = "\n    private fun projectRoot()"
        assertTrue(source.contains(parityAnchor), source)
        assertTrue(source.contains(parityEnd), source)
        val parityBody = sourceBodyBetweenLast(source, parityAnchor, parityEnd)

        assertTrue(parityBody.contains("""val referenceConfig = cleanIntellijConfigDir("xvfb")"""), parityBody)
        assertTrue(parityBody.contains("""val kotlinConfig = cleanIntellijConfigDir("kotlin")"""), parityBody)
        assertTrue(parityBody.contains("runIntellijAgainstXvfb(referenceImage, url, referenceConfig)"), parityBody)
        assertTrue(parityBody.contains("runIntellijAgainstKotlinServer(port, clientImage, url, kotlinConfig)"), parityBody)
        assertFalse(parityBody.contains("sharedConfig"), parityBody)
        assertFalse(parityBody.contains("cleanIntellijConfigDir()"), parityBody)
        assertTrue(source.contains("""withFileSystemBind(configDir.toString(), "/tmp/idea-config", BindMode.READ_WRITE)"""), source)
    }

    @Test
    fun `intellij parity brackets robot capture with nearest svg frame`() {
        val source = Files.readString(projectRoot().resolve("src/test/kotlin/org/jonnyzzz/xserver/IntellijCommunitySmokeTest.kt"))
        val parityAnchor = "    private fun runIntellijAgainstKotlinServer(port: Int, image: String, url: String?, configDir: Path): IntellijKotlinCapture"
        val parityEnd = "\n    private fun waitForIntellijParityReady("
        assertTrue(source.contains(parityAnchor), source)
        assertTrue(source.contains(parityEnd), source)
        val parityBody = sourceBodyBetweenLast(source, parityAnchor, parityEnd)

        val beforeIndex = parityBody.indexOf("val svgBeforeRobot = waitForStableIntellijSvg(port)")
        val robotIndex = parityBody.indexOf("val robot = visualCapture(capture.stdout)")
        val afterIndex = parityBody.indexOf("""val svgAfterRobot = httpGet(port, "/screen.svg")""")
        val closestIndex = parityBody.indexOf("val svg = closestIntellijSvgToRobot(robot, listOf(svgBeforeRobot, svgAfterRobot))")

        assertTrue(beforeIndex >= 0, parityBody)
        assertTrue(robotIndex > beforeIndex, parityBody)
        assertTrue(afterIndex > robotIndex, parityBody)
        assertTrue(closestIndex > afterIndex, parityBody)
    }

    @Test
    fun `intellij xvfb reference can run bounded extension experiments`() {
        val source = Files.readString(projectRoot().resolve("src/test/kotlin/org/jonnyzzz/xserver/IntellijCommunitySmokeTest.kt"))
        val xvfbAnchor = "    private fun runIntellijAgainstXvfb(image: String, url: String?, configDir: Path): IntellijReferenceCapture ="
        val xvfbEnd = "\n    private fun runIntellijAgainstKotlinServer("
        assertTrue(source.contains(xvfbAnchor), source)
        assertTrue(source.contains(xvfbEnd), source)
        val xvfbBody = sourceBodyBetweenLast(source, xvfbAnchor, xvfbEnd)
        assertTrue(xvfbBody.contains("val xvfbExtraArgs = intellijXvfbExtraArgs()"), xvfbBody)
        assertTrue(xvfbBody.contains("XVFB_EXTRA_ARGS='\${xvfbExtraArgs}'"), xvfbBody)
        assertTrue(xvfbBody.contains(">/tmp/xvfb-extra-args.log"), xvfbBody)
        assertTrue(xvfbBody.contains("Xvfb :99 -screen 0"), xvfbBody)
        assertTrue(xvfbBody.contains("XVFB_EXTRA_ARGS >/tmp/xvfb.log"), xvfbBody)
        assertTrue(xvfbBody.contains("intellij-xvfb-extra-args.log"), xvfbBody)
    }

    @Test
    fun `intellij glx jcef diagnostics summary extracts preflight and angle failures`() {
        val kotlinText =
            """
            - #8 QueryServerString minor=19 screen=0 name=3 value=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile GLX_EXT_create_context_es2_profile
            - #7 SetClientInfo2ARB minor=35 layout=spec client=1.4 versions=1 glBytes=14 glxBytes=67 glExtensions=GL_EXT_texture glxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile
            - #6 CreatePbuffer minor=27 screen=0 fbconfig=0x28 pbuffer=0x1800001 attribs=2 attrs=[0x8041=1, 0x8040=1]
            - #5 CreateContextAttribsARB minor=34 context=0x1800002 fbconfig=0x28 screen=0 share=0x0 direct=true attribs=3 attrs=[0x2091=4, 0x2092=5, 0x9126=1]
            - #4 Error minor=34 request=CreateContextAttribsARB error=167 badValue=0x1800002 sequence=4
            """.trimIndent()
        val logs = listOf(
            IntellijLogArtifact(
                fileName = "intellij-xvfb-glx-xdpyinfo.log",
                text =
                    """
                    GLX version: 1.4
                    GLX extensions:
                        GLX_ARB_create_context, GLX_EXT_create_context_es_profile

                    GLX visuals:
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-glx-xdpyinfo.log",
                text =
                    """
                    GLX extension not supported by xdpyinfo
                    number of extensions:    2
                        GLX
                        RENDER
                    default screen number:    0
                    GLX version: 1.4
                    GLX extensions:
                        GLX_ARB_create_context GLX_ARB_create_context_profile
                        GLX_EXT_create_context_es_profile GLX_EXT_create_context_es2_profile
                    GLX visuals:
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-run.log",
                text = """
                    No matching fbConfigs or visuals found
                    ANGLE Display::initialize error 12289: Could not create the initialization pbuffer.
                    ANGLE Display::initialize error 12289: Cannot create an OpenGL ES platform on GLX without the GLX_EXT_create_context_es_profile extension.
                    ANGLE Display::initialize error 12289: OpenGL ES 2.0 is not supportable.
                    ANGLE Display::initialize error 12289: Cannot create an OpenGL ES platform on GLX without the GLX_ARB_create_context extension.
                """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-xvfb-run.log",
                text = "xvfb run without ANGLE pbuffer/profile errors",
            ),
        )
        val summary = intellijGlxJcefDiagnosticsSummary(
            logs = logs + IntellijLogArtifact(fileName = "intellij-kotlin-text.txt", text = kotlinText),
        )
        val summaryFromExplicitText = intellijGlxJcefDiagnosticsSummary(
            logs,
            kotlinText = kotlinText,
            kotlinStateJson = """{"propertyOperations":[{"operation":"GetProperty"}]}""",
        )

        assertTrue(summary.contains("xvfbGlxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile"), summary)
        assertTrue(
            summary.contains("kotlinGlxExtensions=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es2_profile GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(summary.contains("kotlinListsGlxExtension=true"), summary)
        assertTrue(summary.contains("kotlinXdpyinfoGlxDetailUnsupported=true"), summary)
        assertTrue(
            summary.contains("kotlinClientGlxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(
            summary.contains("kotlinServerGlxExtensionsFromTrace=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es2_profile GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(
            summaryFromExplicitText.contains("kotlinClientGlxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile"),
            summaryFromExplicitText,
        )
        assertTrue(
            summaryFromExplicitText.contains("kotlinServerGlxExtensionsFromTrace=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es2_profile GLX_EXT_create_context_es_profile"),
            summaryFromExplicitText,
        )
        assertTrue(summary.contains("kotlinGlxLifecycleOperations=CreateContextAttribsARB CreatePbuffer"), summary)
        assertTrue(
            summary.contains(
                "kotlinGlxLifecycleTrace=- #6 CreatePbuffer minor=27 screen=0 fbconfig=0x28 pbuffer=0x1800001 attribs=2 attrs=[0x8041=1, 0x8040=1] | - #5 CreateContextAttribsARB minor=34 context=0x1800002 fbconfig=0x28 screen=0 share=0x0 direct=true attribs=3 attrs=[0x2091=4, 0x2092=5, 0x9126=1]",
            ),
            summary,
        )
        assertTrue(
            summary.contains("kotlinGlxProtocolErrors=- #4 Error minor=34 request=CreateContextAttribsARB error=167 badValue=0x1800002 sequence=4"),
            summary,
        )
        assertTrue(summary.contains("kotlinExplicitTextTraceIncluded=false"), summary)
        assertTrue(summaryFromExplicitText.contains("kotlinExplicitTextTraceIncluded=true"), summaryFromExplicitText)
        assertTrue(summary.contains("kotlinExplicitStateJsonIncluded=false"), summary)
        assertTrue(summaryFromExplicitText.contains("kotlinExplicitStateJsonIncluded=true"), summaryFromExplicitText)
        assertTrue(summary.contains("xvfbTraceArtifacts=intellij-xvfb-glx-xdpyinfo.log intellij-xvfb-run.log"), summary)
        assertTrue(summary.contains("xvfbAngleInitializationPbufferFailure=false"), summary)
        assertTrue(summary.contains("xvfbAngleMissingEsProfileMessage=false"), summary)
        assertTrue(summary.contains("xvfbAngleNoMatchingFbConfigsOrVisuals=false"), summary)
        assertTrue(summary.contains("xvfbAngleEs2NotSupportable=false"), summary)
        assertTrue(summary.contains("xvfbAngleMissingArbCreateContextMessage=false"), summary)
        assertTrue(summary.contains("kotlinAngleInitializationPbufferFailure=true"), summary)
        assertTrue(summary.contains("kotlinAngleMissingEsProfileMessage=true"), summary)
        assertTrue(summary.contains("kotlinAngleNoMatchingFbConfigsOrVisuals=true"), summary)
        assertTrue(summary.contains("kotlinAngleEs2NotSupportable=true"), summary)
        assertTrue(summary.contains("kotlinAngleMissingArbCreateContextMessage=true"), summary)
        assertTrue(
            summary.contains(
                "kotlinAngleFailureSignatures=initialization-pbuffer missing-es-profile no-matching-fbconfigs-or-visuals es2-not-supportable missing-arb-create-context",
            ),
            summary,
        )
    }

    @Test
    fun `intellij debug flag defaults from environment and honors system property`() {
        val previous = System.getProperty("x.intellijDebug")
        try {
            System.clearProperty("x.intellijDebug")
            assertEquals(System.getenv("X_INTELLIJ_DEBUG") == "true", intellijDebugEnabled())

            System.setProperty("x.intellijDebug", "true")
            assertTrue(intellijDebugEnabled())
            assertEquals("true", intellijDebugValue())

            System.setProperty("x.intellijDebug", "false")
            assertEquals(System.getenv("X_INTELLIJ_DEBUG") == "true", intellijDebugEnabled())
        } finally {
            if (previous == null) {
                System.clearProperty("x.intellijDebug")
            } else {
                System.setProperty("x.intellijDebug", previous)
            }
        }
    }

    @Test
    fun `intellij community from github releases starts against kotlin x server`() {
        assumeTrue(
            System.getProperty("x.intellijSmoke") == "true" || System.getenv("X_INTELLIJ_SMOKE") == "true",
            "Set -Dx.intellijSmoke=true or X_INTELLIJ_SMOKE=true to download and run the heavyweight IntelliJ smoke",
        )
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

        val port = 6208
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        val url = System.getProperty("x.intellijUrl") ?: System.getenv("X_INTELLIJ_URL")
        val image = System.getProperty("x.intellijImage")
            ?: System.getenv("X_INTELLIJ_IMAGE")
            ?: "jonnyzzz-x/x11-client:latest"
        assumeTrue(imageExists(image), "Build $image first with ./gradlew dockerBuildX11Client")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 1280, height = 900)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            intellijContainer(image)
                .use { container ->
                    container.start()
                    val display = port - 6000
                    val result = execIntellijShell(
                        container,
                        """
                        set -eu
                        command -v run-intellij
                        command -v git
                        test -d /tmp/idea-cache
                        check() {
                          echo "check: ${'$'}*"
                          "${'$'}@"
                        }
                        if [ -n "${url.orEmpty()}" ]; then
                          export IDEA_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display \
                        IDEA_X11_DEBUG=${intellijDebugValue()} \
                        IDEA_PROJECT=/workspace/jonnyzzz-x \
                        IDEA_TRUST_PROJECT=true \
                        run-intellij >/tmp/idea-run-smoke.log 2>&1 &
                        pid=${'$'}!
                        echo "${'$'}pid" >/tmp/idea-smoke.pid
                        opened=0
                        for _ in ${'$'}(seq 1 $IntellijOpenWaitSeconds); do
                          if grep -q "Project frame set to Project(name=" /tmp/idea-log/idea.log 2>/dev/null; then
                            opened=1
                            break
                          fi
                          if ! kill -0 "${'$'}pid" 2>/dev/null; then
                            break
                          fi
                          sleep 1
                        done
                        if [ "${'$'}opened" -ne 1 ]; then
                          cat /tmp/idea-run-smoke.log 2>/dev/null || true
                          tail -200 /tmp/idea-log/idea.log 2>/dev/null || true
                          exit 1
                        fi
                        check test -x /usr/lib/jvm/jbr-25/bin/java
                        check grep -q 'name value="jbr-25"' /tmp/idea-config/options/jdk.table.xml
                        check grep -q 'name value="corretto-25"' /tmp/idea-config/options/jdk.table.xml
                        check grep -q 'ide.experimental.ui.onboarding' /tmp/idea-config/options/ide.general.xml
                        check grep -q 'name="differentiateProjects" value="false"' /tmp/idea-config/options/ui.lnf.xml
                        check grep -q 'experimental.ui.on.first.startup' /tmp/idea-config/options/other.xml
                        check sh -lc 'find /root/.java/.userPrefs/jetbrains -name prefs.xml -exec grep -l "euacommunity_accepted_version" {} + | grep -q .'
                        check grep -q -- "-Didea.trust.all.projects=true" /tmp/idea-extra.vmoptions
                        check grep -q -- "-Dremote.x11.workaround=false" /tmp/idea-extra.vmoptions
                        check test -d /tmp/idea-config/plugins
                        check grep -q 'idea.plugins.path=/tmp/idea-config/plugins' /tmp/idea.properties
                        check grep -q "componentStore=/workspace/jonnyzzz-x" /tmp/idea-log/idea.log
                        check grep -qx "\\[run-intellij\\] launcher=/opt/idea/bin/idea" /tmp/idea-run-smoke.log
                        if grep -q "Download JDK" /tmp/idea-log/idea.log; then echo "unexpected Download JDK log"; exit 1; fi
                        if grep -q "Cannot Run Git" /tmp/idea-log/idea.log; then echo "unexpected Cannot Run Git log"; exit 1; fi
                        if grep -q "Project is not trusted" /tmp/idea-log/idea.log; then echo "unexpected Project is not trusted log"; exit 1; fi
                        if grep -q "ide.script.launcher.used" /tmp/idea-log/idea.log /tmp/idea-run-smoke.log 2>/dev/null; then echo "unexpected script launcher warning"; exit 1; fi
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    dumpIntellijLogArtifacts(
                        collectIntellijLogs(container, "intellij-smoke", "/tmp/idea-run-smoke.log"),
                    )
                    assertFalse(
                        execContainerShell(container, 30, "grep -q 'Project is not trusted' /tmp/idea-log/idea.log").exitCode == 0,
                        "IntelliJ should not reject the mounted jonnyzzz-x project as untrusted",
                    )
                    try {
                        val snapshot = waitForVisibleIntellijPixels(port)
                        val stats = snapshot.stats
                        val text = snapshot.text
                        assertTrue(stats.isNotEmpty(), "IntelliJ smoke should expose embedded framebuffer PNGs\n$text")
                        assertTrue(
                            text.contains("Content window"),
                            "IntelliJ smoke should expose the rendered IDE content window in the HTTP report\n$text",
                        )
                        assertFalse(
                            text.contains("Download SDK") || text.contains("Download JDK"),
                            "IntelliJ smoke should not render an SDK/JDK modal in the target surface\n$text",
                        )
                        assertTrue(
                            text.contains("Unsupported requests:\n- None."),
                            "IntelliJ smoke should not leave unsupported protocol requests in the target-client trace\n$text",
                        )
                        assertTrue(
                            stats.any { it.hasVisibleContent() },
                            "IntelliJ screen SVG should contain non-white rendered pixels, got $stats\n$text",
                        )
                        assertTrue(
                            stats.any { it.hasLargeVisibleSurface() },
                            "IntelliJ screen SVG should contain a large rendered IDE surface, got $stats\n$text",
                        )
                    } finally {
                        execContainerShell(
                            container,
                            30,
                            "kill $(cat /tmp/idea-smoke.pid 2>/dev/null || pgrep -f run-intellij) 2>/dev/null || true",
                        )
                    }
                }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `intellij community robot and svg roughly match xvfb reference`() {
        assumeTrue(
            System.getProperty("x.intellijParity") == "true" || System.getenv("X_INTELLIJ_PARITY") == "true",
            "Set -Dx.intellijParity=true or X_INTELLIJ_PARITY=true to run the heavyweight IntelliJ Xvfb parity probe",
        )
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

        val port = 6231
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        val url = System.getProperty("x.intellijUrl") ?: System.getenv("X_INTELLIJ_URL")
        val clientImage = System.getProperty("x.intellijImage")
            ?: System.getenv("X_INTELLIJ_IMAGE")
            ?: "jonnyzzz-x/x11-client:latest"
        val referenceImage = System.getProperty("x.intellijReferenceImage")
            ?: System.getenv("X_INTELLIJ_REFERENCE_IMAGE")
            ?: "jonnyzzz-x/x11-reference:latest"
        assumeTrue(imageExists(clientImage), "Build $clientImage first with scripts/run-supervised.sh gradle dockerBuildX11Client")
        assumeTrue(imageExists(referenceImage), "Build $referenceImage first with scripts/run-supervised.sh gradle dockerBuildX11Images")

        val referenceConfig = cleanIntellijConfigDir("xvfb")
        val kotlinConfig = cleanIntellijConfigDir("kotlin")
        val reference = runIntellijAgainstXvfb(referenceImage, url, referenceConfig)
        val actual = runIntellijAgainstKotlinServer(port, clientImage, url, kotlinConfig)

        val composedSvg = composeSvgLayers(actual.svgLayers, IntellijCaptureWidth, IntellijCaptureHeight)
        val composedSvgCapture = visualCapture(composedSvg)
        dumpIntellijParityArtifacts(
            reference = reference,
            actual = actual,
            composedSvg = composedSvg,
            composedSvgCapture = composedSvgCapture,
        )

        assertTrue(actual.text.contains("Content window"), actual.text)
        assertIntellijHtmlPreviewHasLargeSurface(actual.html, actual.text)
        assertTrue(actual.text.contains("Unsupported requests:\n- None."), actual.text)
        assertFalse(actual.text.contains("Download SDK") || actual.text.contains("Download JDK"), actual.text)
        assertIntellijRuntimeUiDiagnosticsPresent(reference.logs + actual.logs)

        assertIntellijVisualClose(reference.robot, actual.robot, "Kotlin Robot IntelliJ capture")
        assertIntellijVisualClose(reference.robot, composedSvgCapture, "Kotlin SVG-composed IntelliJ framebuffer")
    }

    private fun projectRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

    private fun sourceBodyBetweenLast(source: String, start: String, end: String): String {
        val endIndex = source.lastIndexOf(end)
        assertTrue(endIndex >= 0, source)
        val startIndex = source.lastIndexOf(start, endIndex)
        assertTrue(startIndex >= 0, source)
        return source.substring(startIndex + start.length, endIndex).also {
            assertTrue(it.isNotBlank(), source)
        }
    }

    private fun cleanProjectExport(): Path {
        val root = projectRoot()
        val export = root.resolve("build/tmp/intellij-community-smoke/project").normalize()
        val buildRoot = root.resolve("build").normalize()
        check(export.startsWith(buildRoot)) { "Refusing to delete project export outside build/: $export" }
        export.toFile().deleteRecursively()
        Files.createDirectories(export)

        val process = ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ls-files failed with exit code $exitCode:\n${output.decodeToString()}"
        }

        output.decodeToString()
            .split('\u0000')
            .asSequence()
            .filter { it.isNotEmpty() }
            .forEach { relativePath ->
                val source = root.resolve(relativePath).normalize()
                val target = export.resolve(relativePath).normalize()
                check(source.startsWith(root)) { "Tracked path escaped project root: $relativePath" }
                check(target.startsWith(export)) { "Export path escaped project export: $relativePath" }
                Files.createDirectories(target.parent)
                Files.copy(
                    source,
                    target,
                    LinkOption.NOFOLLOW_LINKS,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

        return export
    }

    private fun intellijCacheDir(): Path {
        val root = projectRoot()
        val cache = root.resolve("build/tmp/intellij-community-smoke/idea-cache").normalize()
        val buildTmp = root.resolve("build/tmp").normalize()
        check(cache.startsWith(buildTmp)) { "Refusing to use IntelliJ cache outside build/tmp/: $cache" }
        Files.createDirectories(cache)
        return cache
    }

    private fun cleanIntellijConfigDir(name: String): Path {
        require(name.matches(Regex("[a-z0-9-]+"))) { "Unsafe IntelliJ config directory name: $name" }
        val root = projectRoot()
        val config = root.resolve("build/tmp/intellij-community-smoke/idea-config-$name").normalize()
        val buildTmp = root.resolve("build/tmp").normalize()
        check(config.startsWith(buildTmp)) { "Refusing to use IntelliJ config outside build/tmp/: $config" }
        config.toFile().deleteRecursively()
        Files.createDirectories(config)
        return config
    }

    private fun intellijContainer(image: String, configDir: Path? = null): GenericContainer<*> {
        val container = GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
            .withFileSystemBind(cleanProjectExport().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
            .withFileSystemBind(
                projectRoot().resolve("docker/x11-client/run-intellij.sh").toString(),
                "/usr/local/bin/run-intellij",
                BindMode.READ_ONLY,
            )
            .withFileSystemBind(intellijCacheDir().toString(), "/tmp/idea-cache", BindMode.READ_WRITE)
            .withEnv("IDEA_CACHE_DIR", "/tmp/idea-cache")
            .withCommand("sleep", "900")
        if (configDir != null) {
            container.withFileSystemBind(configDir.toString(), "/tmp/idea-config", BindMode.READ_WRITE)
        }
        return container
    }

    private fun execContainerShell(container: GenericContainer<*>, timeoutSeconds: Int, script: String) =
        container.execInContainer("timeout", "${timeoutSeconds}s", "sh", "-lc", script)

    private fun execIntellijShell(container: GenericContainer<*>, script: String) =
        execContainerShell(container, IntellijContainerCommandTimeoutSeconds, script)

    private fun intellijDebugEnabled(): Boolean =
        System.getProperty("x.intellijDebug") == "true" || System.getenv("X_INTELLIJ_DEBUG") == "true"

    private fun intellijDebugValue(): String =
        if (intellijDebugEnabled()) "true" else "false"

    private fun intellijXvfbExtraArgs(): String =
        (System.getProperty("x.intellijXvfbExtraArgs") ?: System.getenv("X_INTELLIJ_XVFB_EXTRA_ARGS")).orEmpty()
            .also { value ->
                require(Regex("""[- A-Za-z0-9_./:=+]*""").matches(value)) {
                    "Unsafe Xvfb extra args: $value"
                }
            }

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun imageExists(image: String): Boolean =
        runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun waitForVisibleIntellijPixels(port: Int): VisualSnapshot {
        val deadline = System.currentTimeMillis() + 60_000
        var lastFailure: Throwable? = null
        var lastSnapshot = VisualSnapshot(emptyList(), "")
        while (System.currentTimeMillis() < deadline) {
            try {
                val svg = httpGet(port, "/screen.svg")
                val text = httpGet(port, "/text.txt")
                val stats = pngDataUris(svg).map { imageStats(it.id, it.bytes) }
                val snapshot = VisualSnapshot(stats, text)
                lastSnapshot = snapshot
                if (text.contains("Content window") && stats.any { it.hasLargeVisibleSurface() }) return snapshot
            } catch (t: Throwable) {
                lastFailure = t
            }
            Thread.sleep(250)
        }
        val failureMessage = lastFailure?.let { "\nLast polling failure: ${it::class.qualifiedName}: ${it.message}" }.orEmpty()
        assertTrue(
            lastSnapshot.stats.any { it.hasVisibleContent() },
            "IntelliJ screen SVG did not expose non-white multi-color rendered pixels before timeout, got ${lastSnapshot.stats}\n${lastSnapshot.text}$failureMessage",
        )
        return lastSnapshot
    }

    private fun waitForStableIntellijSvg(port: Int): String {
        val visible = waitForVisibleIntellijPixels(port)
        val deadline = System.currentTimeMillis() + 30_000
        var previous: VisualCapture? = null
        var lastSvg = ""
        var lastDistance = Double.POSITIVE_INFINITY
        var stablePairs = 0
        while (System.currentTimeMillis() < deadline) {
            val svg = httpGet(port, "/screen.svg")
            val layers = svgCompositionLayers(svg)
            if (layers.isNotEmpty()) {
                val capture = visualCapture(composeSvgLayers(layers, IntellijCaptureWidth, IntellijCaptureHeight))
                val before = previous
                if (before != null) {
                    lastDistance = imageDistance(before.image, capture.image)
                    stablePairs = if (lastDistance <= 1.0) stablePairs + 1 else 0
                    if (stablePairs >= 2) return svg
                }
                previous = capture
                lastSvg = svg
            }
            Thread.sleep(250)
        }
        assertTrue(
            lastSvg.isNotEmpty(),
            "IntelliJ screen SVG did not expose composable layers while waiting for stability; visible=$visible",
        )
        assertTrue(
            stablePairs >= 2,
            "IntelliJ screen SVG did not stabilize before capture; lastDistance=$lastDistance visible=$visible",
        )
        return lastSvg
    }

    private fun closestIntellijSvgToRobot(
        robot: VisualCapture,
        candidates: List<String>,
        width: Int = IntellijCaptureWidth,
        height: Int = IntellijCaptureHeight,
    ): String {
        val scored = candidates.mapNotNull { svg ->
            val layers = svgCompositionLayers(svg)
            if (layers.isEmpty()) return@mapNotNull null
            val capture = visualCapture(composeSvgLayers(layers, width, height))
            svg to fullImageDistance(robot.image, capture.image)
        }
        return scored.minByOrNull { it.second }?.first
            ?: error("IntelliJ screen SVG did not expose composable layers near Robot capture")
    }

    private fun runIntellijAgainstXvfb(image: String, url: String?, configDir: Path): IntellijReferenceCapture =
        intellijContainer(image, configDir)
            .use { container ->
                container.start()
                compileRobotCapture(container)
                compileIntellijUiDiagnosticsAgent(container)
                val xvfbExtraArgs = intellijXvfbExtraArgs()
                val result = execIntellijShell(
                    container,
                    """
                    set -eu
                    command -v Xvfb
                    command -v run-intellij
                    command -v git
                    test -d /tmp/idea-cache
                    test -x /usr/local/bin/run-intellij
                    cksum /usr/local/bin/run-intellij >/tmp/run-intellij-cksum.log
                    if [ -n "${url.orEmpty()}" ]; then
                      export IDEA_URL="${url.orEmpty()}"
                    fi
                    XVFB_EXTRA_ARGS='${xvfbExtraArgs}'
                    printf '%s\n' "${'$'}XVFB_EXTRA_ARGS" >/tmp/xvfb-extra-args.log
                    Xvfb :99 -screen 0 ${IntellijCaptureWidth}x${IntellijCaptureHeight}x24 ${'$'}XVFB_EXTRA_ARGS >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    echo "${'$'}xvfb" >/tmp/xvfb.pid
                    for _ in ${'$'}(seq 1 80); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xdpyinfo -queryExtensions >/tmp/xdpyinfo-extensions-xvfb.log 2>&1 || true
                    DISPLAY=:99 xdpyinfo -ext GLX >/tmp/xdpyinfo-glx-xvfb.log 2>&1 || true
                    DISPLAY=:99 xsetroot -solid white >/tmp/xsetroot.log 2>&1 || true
                    DISPLAY=:99 \
                    IDEA_X11_DEBUG=${intellijDebugValue()} \
                    IDEA_PROJECT=/workspace/jonnyzzz-x \
                    IDEA_TRUST_PROJECT=true \
                    run-intellij >/tmp/idea-run-xvfb.log 2>&1 &
                    idea=${'$'}!
                    echo "${'$'}idea" >/tmp/idea-xvfb.pid
                    opened=0
                    for _ in ${'$'}(seq 1 $IntellijOpenWaitSeconds); do
                      if grep -q "Project frame set to Project(name=" /tmp/idea-log/idea.log 2>/dev/null; then
                        opened=1
                        break
                      fi
                      if ! kill -0 "${'$'}idea" 2>/dev/null; then
                        break
                      fi
                      sleep 1
                    done
                    if [ "${'$'}opened" -ne 1 ]; then
                      cat /tmp/idea-run-xvfb.log 2>/dev/null || true
                      tail -240 /tmp/idea-log/idea.log 2>/dev/null || true
                      exit 1
                    fi
                    if grep -q "Download JDK" /tmp/idea-log/idea.log; then echo "unexpected Download JDK log"; exit 1; fi
                    if grep -q "Cannot Run Git" /tmp/idea-log/idea.log; then echo "unexpected Cannot Run Git log"; exit 1; fi
                    if grep -q "Project is not trusted" /tmp/idea-log/idea.log; then echo "unexpected Project is not trusted log"; exit 1; fi
                    if grep -q "ide.script.launcher.used" /tmp/idea-log/idea.log /tmp/idea-run-xvfb.log 2>/dev/null; then echo "unexpected script launcher warning"; exit 1; fi
                    grep -qx "\\[run-intellij\\] launcher=/opt/idea/bin/idea" /tmp/idea-run-xvfb.log
                    grep -q -- "-Dremote.x11.workaround=false" /tmp/idea-extra.vmoptions
                    grep -q 'name="differentiateProjects" value="false"' /tmp/idea-config/options/ui.lnf.xml
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                val extraLogs = listOf(
                    "/tmp/xdpyinfo-extensions-xvfb.log" to "intellij-xvfb-extensions-xdpyinfo.log",
                    "/tmp/xdpyinfo-glx-xvfb.log" to "intellij-xvfb-glx-xdpyinfo.log",
                    "/tmp/xwininfo-xvfb-root-tree.log" to "intellij-xvfb-xwininfo-root-tree.log",
                    "/tmp/xprop-xvfb-root.log" to "intellij-xvfb-xprop-root.log",
                    "/tmp/idea-extra.vmoptions" to "intellij-xvfb-idea-extra.vmoptions",
                    "/tmp/idea-config/options/ui.lnf.xml" to "intellij-xvfb-ui-lnf.xml",
                    "/tmp/idea-config-options-inventory.log" to "intellij-xvfb-config-options-inventory.log",
                    "/tmp/run-intellij-env.log" to "intellij-xvfb-run-intellij-env.log",
                    "/tmp/idea-ui-runtime-diagnostics.log" to "intellij-xvfb-ui-runtime-diagnostics.log",
                    "/tmp/run-intellij-cksum.log" to "intellij-xvfb-run-intellij-cksum.log",
                    "/tmp/xvfb-extra-args.log" to "intellij-xvfb-extra-args.log",
                )
                try {
                    waitForIntellijParityReady(
                        container = container,
                        pidPath = "/tmp/idea-xvfb.pid",
                        label = "Xvfb IntelliJ",
                        artifactPrefix = "intellij-xvfb",
                        runLogPath = "/tmp/idea-run-xvfb.log",
                        extraLogs = extraLogs,
                    )
                    captureIntellijRuntimeUiDiagnostics(container, "/tmp/idea-xvfb.pid")
                    captureIntellijConfigOptionsInventory(container)
                    val capture = execIntellijShell(
                        container,
                        """
                        set -eu
                        idea=${'$'}(cat /tmp/idea-xvfb.pid)
                        xvfb=${'$'}(cat /tmp/xvfb.pid)
                        kill -0 "${'$'}idea"
                        kill -0 "${'$'}xvfb"
                        DISPLAY=:99 xwininfo -root -tree >/tmp/xwininfo-xvfb-root-tree.log 2>&1 || true
                        DISPLAY=:99 xprop -root >/tmp/xprop-xvfb-root.log 2>&1 || true
                        DISPLAY=:99 java -cp /tmp XIntellijRobotCapture
                        """.trimIndent(),
                    )
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    val logs = collectIntellijLogs(
                        container = container,
                        prefix = "intellij-xvfb",
                        runLogPath = "/tmp/idea-run-xvfb.log",
                        extraLogs = extraLogs,
                    )
                    IntellijReferenceCapture(
                        robot = visualCapture(capture.stdout),
                        logs = logs,
                    )
                } finally {
                    execContainerShell(
                        container,
                        30,
                        "kill $(cat /tmp/idea-xvfb.pid 2>/dev/null) $(cat /tmp/xvfb.pid 2>/dev/null) 2>/dev/null || true",
                    )
                }
            }

    private fun runIntellijAgainstKotlinServer(port: Int, image: String, url: String?, configDir: Path): IntellijKotlinCapture {
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = IntellijCaptureWidth,
                height = IntellijCaptureHeight,
                rootBackgroundPixel = 0x0000_0000,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            intellijContainer(image, configDir)
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    compileIntellijUiDiagnosticsAgent(container)
                    val display = port - 6000
                    val startResult = execIntellijShell(
                        container,
                        """
                        set -eu
                        command -v run-intellij
                        command -v git
                        test -d /tmp/idea-cache
                        test -x /usr/local/bin/run-intellij
                        cksum /usr/local/bin/run-intellij >/tmp/run-intellij-cksum.log
                        if [ -n "${url.orEmpty()}" ]; then
                          export IDEA_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display xdpyinfo -queryExtensions >/tmp/xdpyinfo-extensions-kotlin.log 2>&1 || true
                        DISPLAY=host.docker.internal:$display xdpyinfo -ext GLX >/tmp/xdpyinfo-glx-kotlin.log 2>&1 || true
                        DISPLAY=host.docker.internal:$display \
                        IDEA_X11_DEBUG=${intellijDebugValue()} \
                        IDEA_PROJECT=/workspace/jonnyzzz-x \
                        IDEA_TRUST_PROJECT=true \
                        run-intellij >/tmp/idea-run-parity.log 2>&1 &
                        pid=${'$'}!
                        echo "${'$'}pid" >/tmp/idea-parity.pid
                        opened=0
                        for _ in ${'$'}(seq 1 $IntellijOpenWaitSeconds); do
                          if grep -q "Project frame set to Project(name=" /tmp/idea-log/idea.log 2>/dev/null; then
                            opened=1
                            break
                          fi
                          if ! kill -0 "${'$'}pid" 2>/dev/null; then
                            break
                          fi
                          sleep 1
                        done
                        if [ "${'$'}opened" -ne 1 ]; then
                          cat /tmp/idea-run-parity.log 2>/dev/null || true
                          tail -240 /tmp/idea-log/idea.log 2>/dev/null || true
                          exit 1
                        fi
                        if grep -q "Download JDK" /tmp/idea-log/idea.log; then echo "unexpected Download JDK log"; exit 1; fi
                        if grep -q "Cannot Run Git" /tmp/idea-log/idea.log; then echo "unexpected Cannot Run Git log"; exit 1; fi
                        if grep -q "Project is not trusted" /tmp/idea-log/idea.log; then echo "unexpected Project is not trusted log"; exit 1; fi
                        if grep -q "ide.script.launcher.used" /tmp/idea-log/idea.log /tmp/idea-run-parity.log 2>/dev/null; then echo "unexpected script launcher warning"; exit 1; fi
                        grep -qx "\\[run-intellij\\] launcher=/opt/idea/bin/idea" /tmp/idea-run-parity.log
                        grep -q -- "-Dremote.x11.workaround=false" /tmp/idea-extra.vmoptions
                        grep -q 'name="differentiateProjects" value="false"' /tmp/idea-config/options/ui.lnf.xml
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)
                    val extraLogs = listOf(
                        "/tmp/xdpyinfo-extensions-kotlin.log" to "intellij-kotlin-extensions-xdpyinfo.log",
                        "/tmp/xdpyinfo-glx-kotlin.log" to "intellij-kotlin-glx-xdpyinfo.log",
                        "/tmp/xwininfo-kotlin-root-tree.log" to "intellij-kotlin-xwininfo-root-tree.log",
                        "/tmp/xprop-kotlin-root.log" to "intellij-kotlin-xprop-root.log",
                        "/tmp/idea-extra.vmoptions" to "intellij-kotlin-idea-extra.vmoptions",
                        "/tmp/idea-config/options/ui.lnf.xml" to "intellij-kotlin-ui-lnf.xml",
                        "/tmp/idea-config-options-inventory.log" to "intellij-kotlin-config-options-inventory.log",
                        "/tmp/run-intellij-env.log" to "intellij-kotlin-run-intellij-env.log",
                        "/tmp/idea-ui-runtime-diagnostics.log" to "intellij-kotlin-ui-runtime-diagnostics.log",
                        "/tmp/run-intellij-cksum.log" to "intellij-kotlin-run-intellij-cksum.log",
                    )
                    try {
                        waitForIntellijParityReady(
                            container = container,
                            pidPath = "/tmp/idea-parity.pid",
                            label = "Kotlin IntelliJ",
                            artifactPrefix = "intellij-kotlin",
                            runLogPath = "/tmp/idea-run-parity.log",
                            extraLogs = extraLogs,
                        )
                        captureIntellijRuntimeUiDiagnostics(container, "/tmp/idea-parity.pid")
                        captureIntellijConfigOptionsInventory(container)
                        execIntellijShell(
                            container,
                            """
                            set -eu
                            DISPLAY=host.docker.internal:$display xwininfo -root -tree >/tmp/xwininfo-kotlin-root-tree.log 2>&1 || true
                            DISPLAY=host.docker.internal:$display xprop -root >/tmp/xprop-kotlin-root.log 2>&1 || true
                            """.trimIndent(),
                        )
                        val svgBeforeRobot = waitForStableIntellijSvg(port)
                        val capture = execIntellijShell(
                            container,
                            """
                            set -eu
                            pid=${'$'}(cat /tmp/idea-parity.pid)
                            kill -0 "${'$'}pid"
                            DISPLAY=host.docker.internal:$display java -Dx.captureDelayMs=0 -cp /tmp XIntellijRobotCapture
                            """.trimIndent(),
                        )
                        assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                        val robot = visualCapture(capture.stdout)
                        val svgAfterRobot = httpGet(port, "/screen.svg")
                        val svg = closestIntellijSvgToRobot(robot, listOf(svgBeforeRobot, svgAfterRobot))
                        val html = httpGet(port, "/")
                        val text = httpGet(port, "/text.txt")
                        val stateJson = httpGet(port, "/state.json")
                        val logs = collectIntellijLogs(
                            container = container,
                            prefix = "intellij-kotlin",
                            runLogPath = "/tmp/idea-run-parity.log",
                            extraLogs = extraLogs,
                        )
                        return IntellijKotlinCapture(
                            robot = robot,
                            text = text,
                            stateJson = stateJson,
                            svg = svg,
                            html = html,
                            svgLayers = svgCompositionLayers(svg),
                            logs = logs,
                        )
                    } finally {
                        execContainerShell(
                            container,
                            30,
                            "kill $(cat /tmp/idea-parity.pid 2>/dev/null || pgrep -f run-intellij) 2>/dev/null || true",
                        )
                        server.close()
                        serverThread.join(1_000)
                    }
                }
        }
    }

    private fun waitForIntellijParityReady(
        container: GenericContainer<*>,
        pidPath: String,
        label: String,
        artifactPrefix: String,
        runLogPath: String,
        extraLogs: List<Pair<String, String>>,
    ) {
        val deadline = System.currentTimeMillis() + IntellijParityReadyWaitSeconds * 1_000L
        var lastOutput = ""
        while (System.currentTimeMillis() < deadline) {
            val result = execContainerShell(
                container,
                30,
                """
                set -eu
                pid=${'$'}(cat '$pidPath')
                if ! kill -0 "${'$'}pid" 2>/dev/null; then
                  echo "idea process ${'$'}pid is not running"
                  exit 3
                fi
                cat /tmp/idea-log/idea.log 2>/dev/null || true
                """.trimIndent(),
            )
            val readiness = intellijParityReadiness(result.stdout)
            if (result.exitCode == 0 && readiness.ready) return
            lastOutput = if (result.exitCode == 0) {
                "missing=${readiness.missing.joinToString(" ")}\n${result.stdout.lines().takeLast(80).joinToString("\n")}"
            } else {
                result.stdout + result.stderr
            }
            Thread.sleep(1_000)
        }
        dumpIntellijLogArtifacts(
            collectIntellijLogs(
                container = container,
                prefix = artifactPrefix,
                runLogPath = runLogPath,
                extraLogs = extraLogs,
            ),
        )
        error("$label did not reach comparable parity capture readiness in ${IntellijParityReadyWaitSeconds}s\n$lastOutput")
    }

    private fun intellijParityReadiness(log: String): IntellijParityReadiness {
        val lines = log.lines()
        val projectViewIndex = lines.indexOfFirst { it.contains("Project View initialization completed") }
        val readmeOpenedIndex = lines.indexOfFirst { it.contains("fileOpened README.md") }
        val markdownPreviewIndex = lines.indexOfFirst { it.contains("MarkdownPreviewFileEditor: setHtml finished") }
        val postMarkdownIndexingIndex = if (markdownPreviewIndex >= 0) {
            lines.indexOfFirstIndexed(markdownPreviewIndex + 1) { it.contains("UnindexedFilesIndexer - Finished") }
        } else {
            -1
        }
        val missing = buildList {
            if (projectViewIndex < 0) add("project-view")
            if (readmeOpenedIndex < 0) add("readme-opened")
            if (markdownPreviewIndex < 0) add("markdown-preview")
            if (markdownPreviewIndex >= 0 && postMarkdownIndexingIndex < 0) add("post-markdown-indexing")
        }
        return IntellijParityReadiness(missing.isEmpty(), missing)
    }

    private inline fun List<String>.indexOfFirstIndexed(startIndex: Int, predicate: (String) -> Boolean): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                if (isInsideHiddenSvgGroup(svg, match.range.first)) return@mapNotNull null
                val tag = match.value
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val x = svgIntAttribute(tag, "x") ?: return@mapNotNull null
                val y = svgIntAttribute(tag, "y") ?: return@mapNotNull null
                val width = svgIntAttribute(tag, "width") ?: return@mapNotNull null
                val height = svgIntAttribute(tag, "height") ?: return@mapNotNull null
                EmbeddedPng(
                    id = id,
                    bytes = Base64.getDecoder().decode(encoded),
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                )
            }
            .toList()

    private fun svgIntAttribute(tag: String, name: String): Int? =
        Regex("""\b$name="(-?\d+)"""").find(tag)?.groupValues?.get(1)?.toInt()

    private fun svgCompositionLayers(svg: String): List<SvgLayer> {
        val clipRectangles = svgClipRectangles(svg)
        val layers = Regex("""<(?:image|rect)\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                if (isInsideHiddenSvgGroup(svg, match.range.first)) return@mapNotNull null
                val tag = match.value
                val x = svgIntAttribute(tag, "x") ?: return@mapNotNull null
                val y = svgIntAttribute(tag, "y") ?: return@mapNotNull null
                val width = svgIntAttribute(tag, "width") ?: return@mapNotNull null
                val height = svgIntAttribute(tag, "height") ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)""").find(tag)?.groupValues?.get(1)
                if (encoded == null) {
                    if (!tag.hasSvgClass("window-border")) return@mapNotNull null
                    val id = Regex("""\bdata-border-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: "window-border"
                    val fill = Regex("""\bfill="#([0-9a-fA-F]{6})"""").find(tag)?.groupValues?.get(1)?.toInt(16)
                        ?: return@mapNotNull null
                    return@mapNotNull SvgLayer(
                        id = id,
                        source = "window-border",
                        bytes = null,
                        fill = 0xff00_0000.toInt() or fill,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        clipRectangles = svgClipPathId(tag)?.let { clipRectangles[it] }.orEmpty(),
                    )
                }
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: "framebuffer"
                SvgLayer(
                    id = id,
                    source = svgStringAttribute(tag, "data-source") ?: "framebuffer",
                    bytes = Base64.getDecoder().decode(encoded),
                    fill = null,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    clipRectangles = svgClipPathId(tag)
                        ?.let { clipRectangles[it] }
                        ?: clipRectangles["clip-screen-${id.removePrefix("0x")}"].orEmpty(),
                )
            }
            .toList()
        return layers.filter { it.source == "composited-root" && it.bytes != null }.ifEmpty { layers }
    }

    private fun isInsideHiddenSvgGroup(svg: String, offset: Int): Boolean {
        val hiddenStack = mutableListOf<Boolean>()
        Regex("""<g\b[^>]*>|</g>""")
            .findAll(svg.substring(0, offset.coerceIn(0, svg.length)))
            .forEach { match ->
                val tag = match.value
                if (tag.startsWith("</")) {
                    if (hiddenStack.isNotEmpty()) hiddenStack.removeAt(hiddenStack.lastIndex)
                } else {
                    hiddenStack += svgVisibilityHidden(tag) ?: (hiddenStack.lastOrNull() == true)
                }
            }
        return hiddenStack.lastOrNull() == true
    }

    private fun svgVisibilityHidden(tag: String): Boolean? {
        val visibility = svgStringAttribute(tag, "visibility")
            ?: Regex("""\bstyle\s*=\s*(['"])(.*?)\1""")
                .find(tag)
                ?.groupValues
                ?.get(2)
                ?.split(';')
                ?.mapNotNull { declaration ->
                    val parts = declaration.split(':', limit = 2)
                    parts.getOrNull(1)?.trim().takeIf { parts.getOrNull(0)?.trim() == "visibility" }
                }
                ?.lastOrNull()
        return when (visibility?.trim()) {
            "hidden", "collapse" -> true
            "visible" -> false
            else -> null
        }
    }

    private fun svgStringAttribute(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(['"])(.*?)\1""").find(tag)?.groupValues?.get(2)

    private fun svgClipPathId(tag: String): String? =
        Regex("""\bclip-path="url\(#([^)"']+)\)"""").find(tag)?.groupValues?.get(1)

    private fun svgClipRectangles(svg: String): Map<String, List<Rectangle>> =
        Regex("""<clipPath\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</clipPath>""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(svg)
            .associate { match ->
                val rectangles = Regex("""<rect\b[^>]*>""")
                    .findAll(match.groupValues[2])
                    .mapNotNull { rect ->
                        val tag = rect.value
                        val x = svgIntAttribute(tag, "x") ?: return@mapNotNull null
                        val y = svgIntAttribute(tag, "y") ?: return@mapNotNull null
                        val width = svgIntAttribute(tag, "width") ?: return@mapNotNull null
                        val height = svgIntAttribute(tag, "height") ?: return@mapNotNull null
                        Rectangle(x, y, width, height)
                    }
                    .toList()
                match.groupValues[1] to rectangles
            }

    private fun String.hasSvgClass(className: String): Boolean =
        Regex("""\bclass="([^"]*)"""")
            .findAll(this)
            .any { match -> match.groupValues[1].split(' ').any { it == className } }

    private fun htmlWindowPreviewSurfaces(html: String): List<HtmlPreviewSurface> =
        Regex(
            """<svg\b(?=[^>]*\bclass="[^"]*\bwindow-preview-svg\b)([^>]*)>(.*?)</svg>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
            .findAll(html)
            .flatMap { svgMatch ->
                val svgTag = svgMatch.groupValues[1]
                val body = svgMatch.groupValues[2]
                val viewBoxParts = svgStringAttribute(svgTag, "viewBox")
                    ?.trim()
                    ?.split(Regex("""\s+"""))
                    .orEmpty()
                val viewWidth = viewBoxParts.getOrNull(2)?.toDoubleOrNull()?.toInt() ?: 0
                val viewHeight = viewBoxParts.getOrNull(3)?.toDoubleOrNull()?.toInt() ?: 0
                val label = svgStringAttribute(svgTag, "aria-label").orEmpty()
                Regex("""<image\b[^>]*>""")
                    .findAll(body)
                    .mapNotNull { imageMatch ->
                        val imageTag = imageMatch.value
                        if (!imageTag.hasSvgClass("framebuffer-image")) return@mapNotNull null
                        val source = svgStringAttribute(imageTag, "data-source") ?: return@mapNotNull null
                        HtmlPreviewSurface(
                            label = label,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                            windowId = svgStringAttribute(imageTag, "data-window-id").orEmpty(),
                            pixmapId = svgStringAttribute(imageTag, "data-pixmap-id"),
                            pictureId = svgStringAttribute(imageTag, "data-picture-id"),
                            source = source,
                            x = svgIntAttribute(imageTag, "x") ?: 0,
                            y = svgIntAttribute(imageTag, "y") ?: 0,
                            width = svgIntAttribute(imageTag, "width") ?: 0,
                            height = svgIntAttribute(imageTag, "height") ?: 0,
                        )
                    }
            }
            .toList()

    private fun assertIntellijHtmlPreviewHasLargeSurface(html: String, text: String = "") {
        assertTrue(html.contains("""class="window-contents""""), "IntelliJ HTML capture must include the window preview section")
        assertTrue(html.contains("Content window"), "IntelliJ HTML capture must include the IDE content-window label")
        val contentWindowIds = contentWindowIdsFromText(text)
        val previews = htmlWindowPreviewSurfaces(html)
        val largeContentPreviews = previews.filter {
            (if (contentWindowIds.isEmpty()) it.label.contains("Content window") else it.windowId in contentWindowIds) &&
                it.viewWidth >= 640 &&
                it.viewHeight >= 360 &&
                it.width >= 640 &&
                it.height >= 360 &&
                it.source in setOf("window-framebuffer", "matching-pixmap", "retained-picture")
        }
        assertTrue(
            largeContentPreviews.isNotEmpty(),
            "IntelliJ HTML capture must expose a large Content window framebuffer/backing surface, previews=$previews",
        )
    }

    private fun contentWindowIdsFromText(text: String): Set<String> =
        Regex("""-\s+(0x[0-9a-fA-F]+)\b[^\n]*\blabel="Content window"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

    private fun imageStats(id: String, bytes: ByteArray): ImageStats {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return ImageStats(id, 0, 0, 0, 0, emptyList())
        var count = 0
        val samples = linkedSetOf<Int>()
        val colors = linkedSetOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                if (samples.size < 8) samples += argb
                val alpha = (argb ushr 24) and 0xff
                val rgb = argb and 0x00ff_ffff
                if (alpha > 0 && rgb != 0x00ff_ffff) {
                    count++
                    if (colors.size < 16) colors += rgb
                }
            }
        }
        return ImageStats(
            id = id,
            width = image.width,
            height = image.height,
            nonWhitePixels = count,
            distinctNonWhiteColors = colors.size,
            samples = samples.map { "0x${it.toUInt().toString(16)}" },
        )
    }

    private fun compileRobotCapture(container: GenericContainer<*>) {
        val result = execContainerShell(
            container,
            120,
            "cat > /tmp/XIntellijRobotCapture.java <<'JAVA'\n${robotCaptureSource()}\nJAVA\njavac /tmp/XIntellijRobotCapture.java",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun compileIntellijUiDiagnosticsAgent(container: GenericContainer<*>) {
        val script =
            "cat > /tmp/XIntellijUiDiagnosticsAgent.java <<'JAVA'\n" +
                intellijUiDiagnosticsAgentSource() +
                "\nJAVA\n" +
                "cat > /tmp/XIntellijUiDiagnosticsAttacher.java <<'JAVA'\n" +
                intellijUiDiagnosticsAttacherSource() +
                "\nJAVA\n" +
                """
                javac --add-modules jdk.attach -d /tmp /tmp/XIntellijUiDiagnosticsAgent.java /tmp/XIntellijUiDiagnosticsAttacher.java
                cat > /tmp/XIntellijUiDiagnosticsAgent.mf <<'EOF'
                Manifest-Version: 1.0
                Agent-Class: XIntellijUiDiagnosticsAgent
                Can-Redefine-Classes: false
                Can-Retransform-Classes: false

                EOF
                jar cfm /tmp/XIntellijUiDiagnosticsAgent.jar /tmp/XIntellijUiDiagnosticsAgent.mf -C /tmp XIntellijUiDiagnosticsAgent.class
                """.trimIndent()
        val result = execContainerShell(
            container,
            120,
            script,
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun captureIntellijRuntimeUiDiagnostics(container: GenericContainer<*>, pidPath: String) {
        val result = execContainerShell(
            container,
            60,
            """
            set +e
            pid=${'$'}(cat '$pidPath' 2>/dev/null)
            rm -f /tmp/idea-ui-runtime-diagnostics.log /tmp/idea-ui-runtime-diagnostics-run.log
            ls -l /tmp/XIntellijUiDiagnostics* >/tmp/idea-ui-runtime-diagnostics-files.log 2>&1
            java --add-modules jdk.attach -cp /tmp XIntellijUiDiagnosticsAttacher "${'$'}pid" /tmp/XIntellijUiDiagnosticsAgent.jar \
              >/tmp/idea-ui-runtime-diagnostics-run.log 2>&1
            status=${'$'}?
            if [ "${'$'}status" -ne 0 ]; then
              {
                echo "agentLoaded=false"
                echo "attachExit=${'$'}status"
                sed 's/^/diagnosticFile=/' /tmp/idea-ui-runtime-diagnostics-files.log 2>/dev/null
                sed 's/^/attachOutput=/' /tmp/idea-ui-runtime-diagnostics-run.log 2>/dev/null
              } > /tmp/idea-ui-runtime-diagnostics.log
            else
              echo "attachExit=0" >> /tmp/idea-ui-runtime-diagnostics.log
              sed 's/^/diagnosticFile=/' /tmp/idea-ui-runtime-diagnostics-files.log >> /tmp/idea-ui-runtime-diagnostics.log 2>/dev/null
              sed 's/^/attachOutput=/' /tmp/idea-ui-runtime-diagnostics-run.log >> /tmp/idea-ui-runtime-diagnostics.log 2>/dev/null
            fi
            exit 0
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun captureIntellijConfigOptionsInventory(container: GenericContainer<*>) {
        val result = execContainerShell(
            container,
            30,
            """
            set +e
            {
              echo "optionsDir=/tmp/idea-config/options"
              if [ -d /tmp/idea-config/options ]; then
                find /tmp/idea-config/options -maxdepth 1 -type f -print | sort | while read file; do
                  echo "--- ${'$'}file"
                  sed -n '1,220p' "${'$'}file" 2>/dev/null
                done
              else
                echo "optionsDirMissing=true"
              fi
            } > /tmp/idea-config-options-inventory.log
            exit 0
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun robotCaptureSource(): String =
        """
        import java.awt.Rectangle;
        import java.awt.Robot;
        import java.awt.image.BufferedImage;
        import java.io.ByteArrayOutputStream;
        import java.util.Base64;
        import javax.imageio.ImageIO;

        public class XIntellijRobotCapture {
          public static void main(String[] args) throws Exception {
            Robot robot = new Robot();
            long delayMs = Long.getLong("x.captureDelayMs", 1200L);
            if (delayMs > 0) Thread.sleep(delayMs);
            BufferedImage image = robot.createScreenCapture(
                new Rectangle(0, 0, $IntellijCaptureWidth, $IntellijCaptureHeight));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
          }
        }
        """.trimIndent()

    private fun intellijUiDiagnosticsAgentSource(): String =
        """
        import java.io.File;
        import java.io.PrintWriter;
        import java.lang.instrument.Instrumentation;
        import java.lang.reflect.Field;
        import java.lang.reflect.Method;

        public class XIntellijUiDiagnosticsAgent {
          public static void premain(String args, Instrumentation inst) {
            writeDiagnostics(args);
          }

          public static void agentmain(String args, Instrumentation inst) {
            writeDiagnostics(args);
          }

          private static void writeDiagnostics(String args) {
            File file = new File(args == null || args.isBlank() ? "/tmp/idea-ui-runtime-diagnostics.log" : args);
            try (PrintWriter out = new PrintWriter(file)) {
              out.println("agentLoaded=true");
              Object uiSettings = callStatic("com.intellij.ide.ui.UISettings", "getInstance");
              Object uiShadow = callStatic("com.intellij.ide.ui.UISettings", "getShadowInstance");
              Object state = call(uiSettings, "getState");
              Object shadowState = call(uiShadow, "getState");
              out.println("runtimeMainMenuDisplayMode=" + call(uiSettings, "getMainMenuDisplayMode"));
              out.println("runtimeStateMainMenuDisplayMode=" + call(state, "getMainMenuDisplayMode"));
              out.println("runtimeShadowStateMainMenuDisplayMode=" + call(shadowState, "getMainMenuDisplayMode"));
              out.println("runtimeMainMenuDisplayModePrev=" + call(uiSettings, "getMainMenuDisplayModePrev"));
              out.println("runtimeShowMainMenu=" + call(uiSettings, "getShowMainMenu"));
              out.println("runtimeStateShowMainMenu=" + call(state, "getShowMainMenu"));
              out.println("runtimeShadowStateShowMainMenu=" + call(shadowState, "getShowMainMenu"));
              out.println("runtimeShowMainToolbar=" + call(uiSettings, "getShowMainToolbar"));
              out.println("runtimeShowNewMainToolbar=" + call(uiSettings, "getShowNewMainToolbar"));
              out.println("runtimeMergeMainMenuWithWindowTitle=" + call(uiSettings, "getMergeMainMenuWithWindowTitle"));
              out.println("runtimeStateModificationCount=" + call(state, "getModificationCount"));
              out.println("runtimeSettingsIdentity=" + System.identityHashCode(uiSettings));
              out.println("runtimeStateIdentity=" + System.identityHashCode(state));
              out.println("runtimeShadowMainMenuDisplayMode=" + call(uiShadow, "getMainMenuDisplayMode"));
              out.println("runtimeShadowShowMainMenu=" + call(uiShadow, "getShowMainMenu"));
              out.println("runtimeShadowShowNewMainToolbar=" + call(uiShadow, "getShowNewMainToolbar"));
              out.println("runtimeShadowMergeMainMenuWithWindowTitle=" + call(uiShadow, "getMergeMainMenuWithWindowTitle"));

              Class<?> headerClass = Class.forName("com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomWindowHeaderUtil");
              Object header = singleton(headerClass);
              Class<?> uiClass = Class.forName("com.intellij.ide.ui.UISettings");
              out.println("runtimeHideNativeLinuxTitleAvailable=" + call(header, "getHideNativeLinuxTitleAvailable${'$'}intellij_platform_ide_impl"));
              out.println("runtimeHideNativeLinuxTitleSupported=" + call(header, "getHideNativeLinuxTitleSupported${'$'}intellij_platform_ide_impl"));
              out.println("runtimeHideNativeLinuxTitleNotSupportedReason=" + call(header, "getHideNativeLinuxTitleNotSupportedReason${'$'}intellij_platform_ide_impl"));
              out.println("runtimeHideNativeLinuxTitle=" + call(header, "hideNativeLinuxTitle${'$'}intellij_platform_ide_impl", new Class<?>[] { uiClass }, uiSettings));
              out.println("runtimeMenuButtonInToolbar=" + call(header, "isMenuButtonInToolbar${'$'}intellij_platform_ide_impl", new Class<?>[] { uiClass }, uiSettings));
              out.println("runtimeDecoratedMenu=" + call(header, "isDecoratedMenu${'$'}intellij_platform_ide_impl", new Class<?>[] { uiClass }, uiSettings));
              out.println("runtimeToolbarInHeader=" + call(header, "isToolbarInHeader${'$'}intellij_platform_ide_impl", new Class<?>[] { uiClass, boolean.class }, uiSettings, false));
              out.println("runtimeCompactHeader=" + call(header, "isCompactHeader${'$'}intellij_platform_ide_impl"));

              out.println("runtimeJbrWindowMoveSupported=" + callStatic("com.jetbrains.JBR", "isWindowMoveSupported"));
              out.println("runtimeStartupIsXToolkit=" + callStatic("com.intellij.util.ui.StartupUiUtil", "isXToolkit"));
              out.println("runtimeStartupIsWaylandToolkit=" + callStatic("com.intellij.util.ui.StartupUiUtil", "isWaylandToolkit"));
              out.println("runtimeX11IsUndefinedDesktop=" + callStatic("com.intellij.openapi.wm.impl.X11UiUtil", "isUndefinedDesktop"));
              out.println("runtimeX11IsTileWM=" + callStatic("com.intellij.openapi.wm.impl.X11UiUtil", "isTileWM"));
              out.println("runtimeX11IsWSL=" + callStatic("com.intellij.openapi.wm.impl.X11UiUtil", "isWSL"));
            } catch (Throwable t) {
              try (PrintWriter out = new PrintWriter(file)) {
                out.println("agentLoaded=false");
                out.println("agentError=" + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(out);
              } catch (Throwable ignored) {
              }
            }
          }

          private static Object singleton(Class<?> type) throws Exception {
            Field field = type.getField("INSTANCE");
            return field.get(null);
          }

          private static Object callStatic(String className, String method) throws Exception {
            Class<?> type = Class.forName(className);
            Method m = type.getMethod(method);
            return m.invoke(null);
          }

          private static Object call(Object target, String method) throws Exception {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
          }

          private static Object call(Object target, String method, Class<?>[] parameterTypes, Object... args) throws Exception {
            Method m = target.getClass().getMethod(method, parameterTypes);
            return m.invoke(target, args);
          }
        }
        """.trimIndent()

    private fun intellijUiDiagnosticsAttacherSource(): String =
        """
        import com.sun.tools.attach.VirtualMachine;

        public class XIntellijUiDiagnosticsAttacher {
          public static void main(String[] args) throws Exception {
            if (args.length != 2) throw new IllegalArgumentException("usage: <pid> <agent-jar>");
            VirtualMachine vm = VirtualMachine.attach(args[0]);
            try {
              vm.loadAgent(args[1], "/tmp/idea-ui-runtime-diagnostics.log");
            } finally {
              vm.detach();
            }
          }
        }
        """.trimIndent()

    private fun runIntellijScriptSource(): String =
        Files.readString(projectRoot().resolve("docker/x11-client/run-intellij.sh"))

    private fun runtimeUiDiagnosticLog(
        mainMenuDisplayMode: String,
        stateMainMenuDisplayMode: String,
        stateModificationCount: Int,
    ): String =
        """
        agentLoaded=true
        runtimeMainMenuDisplayMode=$mainMenuDisplayMode
        runtimeStateMainMenuDisplayMode=$stateMainMenuDisplayMode
        runtimeShadowStateMainMenuDisplayMode=$stateMainMenuDisplayMode
        runtimeMainMenuDisplayModePrev=Hide under Hamburger Button
        runtimeShowMainMenu=true
        runtimeStateShowMainMenu=true
        runtimeShadowStateShowMainMenu=true
        runtimeShowMainToolbar=false
        runtimeStateModificationCount=$stateModificationCount
        runtimeSettingsIdentity=101
        runtimeStateIdentity=202
        runtimeMenuButtonInToolbar=true
        runtimeHideNativeLinuxTitleNotSupportedReason=INCOMPATIBLE_JBR
        runtimeJbrWindowMoveSupported=false
        runtimeStartupIsXToolkit=true
        """.trimIndent()

    private fun composeSvgLayers(layers: List<SvgLayer>, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, width, height)
            for (embedded in layers) {
                if (embedded.bytes != null) {
                    val layer = ImageIO.read(ByteArrayInputStream(embedded.bytes)) ?: continue
                    if (embedded.clipRectangles.isEmpty()) {
                        graphics.drawImage(layer, embedded.x, embedded.y, embedded.width, embedded.height, null)
                    } else {
                        val originalClip = graphics.clip
                        for (clip in embedded.clipRectangles) {
                            graphics.clip = clip
                            graphics.drawImage(layer, embedded.x, embedded.y, embedded.width, embedded.height, null)
                        }
                        graphics.clip = originalClip
                    }
                } else if (embedded.fill != null) {
                    graphics.color = java.awt.Color(embedded.fill, true)
                    if (embedded.clipRectangles.isEmpty()) {
                        graphics.fillRect(embedded.x, embedded.y, embedded.width, embedded.height)
                    } else {
                        val originalClip = graphics.clip
                        for (clip in embedded.clipRectangles) {
                            graphics.clip = clip
                            graphics.fillRect(embedded.x, embedded.y, embedded.width, embedded.height)
                        }
                        graphics.clip = originalClip
                    }
                }
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun dumpIntellijParityArtifacts(
        reference: IntellijReferenceCapture,
        actual: IntellijKotlinCapture,
        composedSvg: BufferedImage,
        composedSvgCapture: VisualCapture,
    ) {
        val directory = intellijSmokeArtifactsDirectory()
        ImageIO.write(reference.robot.image, "png", File(directory, "intellij-xvfb-reference.png"))
        ImageIO.write(actual.robot.image, "png", File(directory, "intellij-kotlin-robot.png"))
        ImageIO.write(composedSvg, "png", File(directory, "intellij-kotlin-svg-composed.png"))
        File(directory, "intellij-kotlin-screen.svg").writeText(actual.svg)
        File(directory, "intellij-kotlin.html").writeText(actual.html)
        File(directory, "intellij-kotlin-text.txt").writeText(actual.text)
        dumpIntellijRenderBandArtifacts(directory, actual.text)
        File(directory, "intellij-kotlin-state.json").writeText(actual.stateJson)
        File(directory, "intellij-kotlin-svg-layers.txt").writeText(svgLayerInventory(actual.svgLayers))
        File(directory, "intellij-kotlin-html-previews.txt").writeText(htmlPreviewInventory(htmlWindowPreviewSurfaces(actual.html)))
        val logs = reference.logs + actual.logs
        dumpIntellijLogArtifacts(logs)
        File(directory, "intellij-glx-jcef-diagnostics.txt").writeText(
            intellijGlxJcefDiagnosticsSummary(
                logs,
                kotlinText = actual.text,
                kotlinStateJson = actual.stateJson,
            ),
        )
        File(directory, "intellij-ui-diagnostics.txt").writeText(intellijUiDiagnosticsSummary(logs))
        dumpIntellijVisualDiff(directory, "intellij-kotlin-robot-vs-xvfb", reference.robot, actual.robot)
        dumpIntellijVisualDiff(directory, "intellij-kotlin-svg-vs-xvfb", reference.robot, composedSvgCapture)
        dumpIntellijVisualDiff(directory, "intellij-kotlin-robot-vs-svg", actual.robot, composedSvgCapture)
        File(directory, "intellij-visual-region-metrics.txt").writeText(
            intellijVisualRegionMetrics(
                text = actual.text,
                expected = reference.robot,
                actualRobot = actual.robot,
                actualSvg = composedSvgCapture,
            ),
        )
    }

    private fun dumpIntellijLogArtifacts(logs: List<IntellijLogArtifact>) {
        val directory = intellijSmokeArtifactsDirectory()
        logs.forEach { artifact ->
            File(directory, artifact.fileName).writeText(artifact.text)
        }
    }

    private fun assertIntellijRuntimeUiDiagnosticsPresent(logs: List<IntellijLogArtifact>) {
        val summary = intellijUiDiagnosticsSummary(logs)
        val requiredLines = listOf(
            "xvfbRuntimeAgentLoaded=true",
            "kotlinRuntimeAgentLoaded=true",
        )
        requiredLines.forEach { line ->
            assertTrue(summary.contains(line), "Missing required IntelliJ runtime UI diagnostic: $line\n$summary")
        }

        val requiredFields = listOf(
            "RuntimeMainMenuDisplayMode",
            "RuntimeStateMainMenuDisplayMode",
            "RuntimeShadowStateMainMenuDisplayMode",
            "RuntimeMainMenuDisplayModePrev",
            "RuntimeShowMainMenu",
            "RuntimeStateShowMainMenu",
            "RuntimeShadowStateShowMainMenu",
            "RuntimeShowMainToolbar",
            "RuntimeStateModificationCount",
            "RuntimeSettingsIdentity",
            "RuntimeStateIdentity",
            "RuntimeMenuButtonInToolbar",
            "RuntimeHideNativeLinuxTitleNotSupportedReason",
            "RuntimeJbrWindowMoveSupported",
            "RuntimeStartupIsXToolkit",
        )
        val missingFields = listOf("xvfb", "kotlin").flatMap { prefix ->
            requiredFields.mapNotNull { field ->
                val linePrefix = "$prefix$field="
                val line = summary.lineSequence().firstOrNull { it.startsWith(linePrefix) }
                if (line == null || line == "$linePrefix<missing>") "$prefix$field" else null
            }
        }
        assertTrue(
            missingFields.isEmpty(),
            "Incomplete IntelliJ runtime UI diagnostics: ${missingFields.joinToString(" ")}\n$summary",
        )

        val comparableFields = listOf(
            "RuntimeMainMenuDisplayMode",
            "RuntimeStateMainMenuDisplayMode",
            "RuntimeShadowStateMainMenuDisplayMode",
            "RuntimeMainMenuDisplayModePrev",
            "RuntimeShowMainMenu",
            "RuntimeStateShowMainMenu",
            "RuntimeShadowStateShowMainMenu",
            "RuntimeShowMainToolbar",
            "RuntimeStateModificationCount",
            "RuntimeMenuButtonInToolbar",
            "RuntimeHideNativeLinuxTitleNotSupportedReason",
            "RuntimeJbrWindowMoveSupported",
            "RuntimeStartupIsXToolkit",
        )
        val divergentFields = comparableFields.mapNotNull { field ->
            val xvfbValue = summaryValue(summary, "xvfb$field")
            val kotlinValue = summaryValue(summary, "kotlin$field")
            if (xvfbValue == kotlinValue) null else "$field: xvfb=$xvfbValue kotlin=$kotlinValue"
        }
        assertTrue(
            divergentFields.isEmpty(),
            "IntelliJ runtime UI state diverged between Xvfb and Kotlin: ${divergentFields.joinToString("; ")}\n$summary",
        )
    }

    private fun summaryValue(summary: String, name: String): String =
        summary.lineSequence()
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?: "<missing>"

    private fun dumpIntellijRenderBandArtifacts(directory: File, text: String) {
        mapOf(
            "top-frame" to "top",
            "right-frame" to "right",
            "bottom-frame" to "bottom",
        ).forEach { (fileBand, reportBand) ->
            File(directory, "intellij-kotlin-$fileBand-render-operations.txt").writeText(
                intellijRenderBandSection(text, reportBand).ifBlank {
                    "RENDER operations intersecting $reportBand mapped root-child band:\n- None.\n"
                },
            )
        }
    }

    private fun intellijSmokeArtifactsDirectory(): File =
        projectRoot().resolve("build/tmp/intellij-community-smoke").toFile().also { it.mkdirs() }

    private fun collectIntellijLogs(
        container: GenericContainer<*>,
        prefix: String,
        runLogPath: String,
        extraLogs: List<Pair<String, String>> = emptyList(),
    ): List<IntellijLogArtifact> {
        val fixedPaths = listOf(
            runLogPath to "$prefix-run.log",
            "/tmp/idea-log/idea.log" to "$prefix-idea.log",
            "/tmp/idea-log/xawt-trace.log" to "$prefix-xawt-trace.log",
            "/tmp/idea-log/jcef.log" to "$prefix-jcef.log",
            "/tmp/idea-log/jcef_chromium.log" to "$prefix-jcef-chromium.log",
        ) + extraLogs
        val dynamicLogs = execContainerShell(
            container,
            30,
            "if [ -d /tmp/idea-log ]; then find /tmp/idea-log -maxdepth 1 -type f \\( -name 'jcef*.log' -o -name 'jcef_chromium*.log' -o -name 'mesa*.log' \\) -print | sort; fi",
        )
        val pathsAndNames = (
            fixedPaths +
                if (dynamicLogs.exitCode == 0) {
                    dynamicLogs.stdout
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .map { path -> path to intellijLogArtifactName(prefix, path) }
                        .toList()
                } else {
                    emptyList()
                }
            ).distinctBy { (path, _) -> path }
        return pathsAndNames.mapNotNull { (path, fileName) ->
            val result = execContainerShell(
                container,
                30,
                "if [ -f '$path' ]; then cat '$path'; fi",
            )
            if (result.exitCode == 0 && result.stdout.isNotEmpty()) {
                IntellijLogArtifact(fileName = fileName, text = result.stdout)
            } else {
                null
            }
        }
    }

    private fun intellijGlxJcefDiagnosticsSummary(
        logs: List<IntellijLogArtifact>,
        kotlinText: String? = null,
        kotlinStateJson: String? = null,
    ): String {
        val xvfbGlx = logs.firstOrNull { it.fileName == "intellij-xvfb-glx-xdpyinfo.log" }?.text.orEmpty()
        val kotlinGlx = logs.firstOrNull { it.fileName == "intellij-kotlin-glx-xdpyinfo.log" }?.text.orEmpty()
        val xvfbTraceArtifacts = logs.filter { it.fileName.startsWith("intellij-xvfb-") }
        val kotlinTraceArtifacts = logs.filter { it.fileName.startsWith("intellij-kotlin-") }
        val xvfbTrace = xvfbTraceArtifacts.joinToString("\n") { it.text }
        val kotlinTrace = (
            kotlinTraceArtifacts.map { it.text } +
                listOfNotNull(kotlinText)
        ).joinToString("\n")
        return buildString {
            appendLine("kotlinExplicitTextTraceIncluded=${!kotlinText.isNullOrBlank()}")
            appendLine("kotlinExplicitStateJsonIncluded=${!kotlinStateJson.isNullOrBlank()}")
            appendLine("xvfbTraceArtifacts=${xvfbTraceArtifacts.joinToString(" ") { it.fileName }}")
            appendLine("kotlinTraceArtifacts=${kotlinTraceArtifacts.joinToString(" ") { it.fileName }}")
            appendLine("xvfbListsGlxExtension=${listedExtensionsFromXdpyinfo(xvfbGlx).contains("GLX")}")
            appendLine("kotlinListsGlxExtension=${listedExtensionsFromXdpyinfo(kotlinGlx).contains("GLX")}")
            appendLine("xvfbXdpyinfoGlxDetailUnsupported=${xdpyinfoGlxDetailUnsupported(xvfbGlx)}")
            appendLine("kotlinXdpyinfoGlxDetailUnsupported=${xdpyinfoGlxDetailUnsupported(kotlinGlx)}")
            appendLine("xvfbGlxExtensions=${glxExtensionsFromXdpyinfo(xvfbGlx).joinToString(" ")}")
            appendLine("kotlinGlxExtensions=${glxExtensionsFromXdpyinfo(kotlinGlx).joinToString(" ")}")
            appendLine("kotlinServerGlxExtensionsFromTrace=${serverGlxExtensionsFromText(kotlinTrace).joinToString(" ")}")
            appendLine("kotlinClientGlxExtensions=${clientGlxExtensionsFromText(kotlinTrace).joinToString(" ")}")
            val lifecycleLines = glxLifecycleOperationLinesFromText(kotlinTrace)
            appendLine("kotlinGlxLifecycleOperations=${glxLifecycleOperationNamesFromLines(lifecycleLines).joinToString(" ")}")
            appendLine("kotlinGlxLifecycleTrace=${lifecycleLines.joinToString(" | ").ifEmpty { "None" }}")
            appendLine("kotlinGlxProtocolErrors=${glxProtocolErrorLinesFromText(kotlinTrace).joinToString(" | ").ifEmpty { "None" }}")
            appendLine("xvfbAngleInitializationPbufferFailure=${angleInitializationPbufferFailure(xvfbTrace)}")
            appendLine("kotlinAngleInitializationPbufferFailure=${angleInitializationPbufferFailure(kotlinTrace)}")
            appendLine("xvfbAngleMissingEsProfileMessage=${angleMissingEsProfileMessage(xvfbTrace)}")
            appendLine("kotlinAngleMissingEsProfileMessage=${angleMissingEsProfileMessage(kotlinTrace)}")
            appendLine("xvfbAngleNoMatchingFbConfigsOrVisuals=${angleNoMatchingFbConfigsOrVisuals(xvfbTrace)}")
            appendLine("kotlinAngleNoMatchingFbConfigsOrVisuals=${angleNoMatchingFbConfigsOrVisuals(kotlinTrace)}")
            appendLine("xvfbAngleEs2NotSupportable=${angleEs2NotSupportable(xvfbTrace)}")
            appendLine("kotlinAngleEs2NotSupportable=${angleEs2NotSupportable(kotlinTrace)}")
            appendLine("xvfbAngleMissingArbCreateContextMessage=${angleMissingArbCreateContextMessage(xvfbTrace)}")
            appendLine("kotlinAngleMissingArbCreateContextMessage=${angleMissingArbCreateContextMessage(kotlinTrace)}")
            appendLine("xvfbAngleFailureSignatures=${angleFailureSignatures(xvfbTrace).joinToString(" ").ifEmpty { "None" }}")
            appendLine("kotlinAngleFailureSignatures=${angleFailureSignatures(kotlinTrace).joinToString(" ").ifEmpty { "None" }}")
        }
    }

    private fun intellijUiDiagnosticsSummary(logs: List<IntellijLogArtifact>): String {
        val xvfbEnv = logs.firstOrNull { it.fileName == "intellij-xvfb-run-intellij-env.log" }?.text.orEmpty()
        val kotlinEnv = logs.firstOrNull { it.fileName == "intellij-kotlin-run-intellij-env.log" }?.text.orEmpty()
        val xvfbUi = logs.firstOrNull { it.fileName == "intellij-xvfb-ui-lnf.xml" }?.text.orEmpty()
        val kotlinUi = logs.firstOrNull { it.fileName == "intellij-kotlin-ui-lnf.xml" }?.text.orEmpty()
        val xvfbExtensions = logs.firstOrNull { it.fileName == "intellij-xvfb-extensions-xdpyinfo.log" }?.text.orEmpty()
        val kotlinExtensions = logs.firstOrNull { it.fileName == "intellij-kotlin-extensions-xdpyinfo.log" }?.text.orEmpty()
        val xvfbRuntime = logs.firstOrNull { it.fileName == "intellij-xvfb-ui-runtime-diagnostics.log" }?.text.orEmpty()
        val kotlinRuntime = logs.firstOrNull { it.fileName == "intellij-kotlin-ui-runtime-diagnostics.log" }?.text.orEmpty()
        val xvfbTrace = logs.filter { it.fileName.startsWith("intellij-xvfb-") }.joinToString("\n") { it.text }
        val kotlinTrace = logs.filter { it.fileName.startsWith("intellij-kotlin-") }.joinToString("\n") { it.text }
        return buildString {
            appendLine("xvfbDisplay=${envValue(xvfbEnv, "DISPLAY")}")
            appendLine("kotlinDisplay=${envValue(kotlinEnv, "DISPLAY")}")
            appendLine("xvfbXdgCurrentDesktop=${envValue(xvfbEnv, "XDG_CURRENT_DESKTOP")}")
            appendLine("kotlinXdgCurrentDesktop=${envValue(kotlinEnv, "XDG_CURRENT_DESKTOP")}")
            appendLine("xvfbXdgSessionType=${envValue(xvfbEnv, "XDG_SESSION_TYPE")}")
            appendLine("kotlinXdgSessionType=${envValue(kotlinEnv, "XDG_SESSION_TYPE")}")
            appendLine("xvfbDesktopSession=${envValue(xvfbEnv, "DESKTOP_SESSION")}")
            appendLine("kotlinDesktopSession=${envValue(kotlinEnv, "DESKTOP_SESSION")}")
            appendLine("xvfbAwtToolkit=${envValue(xvfbEnv, "AWT_TOOLKIT")}")
            appendLine("kotlinAwtToolkit=${envValue(kotlinEnv, "AWT_TOOLKIT")}")
            appendLine("xvfbNonReparenting=${envValue(xvfbEnv, "_JAVA_AWT_WM_NONREPARENTING")}")
            appendLine("kotlinNonReparenting=${envValue(kotlinEnv, "_JAVA_AWT_WM_NONREPARENTING")}")
            appendLine("xvfbRemoteX11Workaround=${envValue(xvfbEnv, "IDEA_REMOTE_X11_WORKAROUND")}")
            appendLine("kotlinRemoteX11Workaround=${envValue(kotlinEnv, "IDEA_REMOTE_X11_WORKAROUND")}")
            appendLine("xvfbIdeaX11Debug=${envValue(xvfbEnv, "IDEA_X11_DEBUG")}")
            appendLine("kotlinIdeaX11Debug=${envValue(kotlinEnv, "IDEA_X11_DEBUG")}")
            appendLine("xvfbMainMenuDisplayMode=${xmlOptionValue(xvfbUi, "mainMenuDisplayMode")}")
            appendLine("kotlinMainMenuDisplayMode=${xmlOptionValue(kotlinUi, "mainMenuDisplayMode")}")
            appendLine("xvfbShowMainMenu=${xmlOptionValue(xvfbUi, "showMainMenu")}")
            appendLine("kotlinShowMainMenu=${xmlOptionValue(kotlinUi, "showMainMenu")}")
            appendLine("xvfbListsXInputExtension=${listedExtensionsFromXdpyinfo(xvfbExtensions).contains("XInputExtension")}")
            appendLine("kotlinListsXInputExtension=${listedExtensionsFromXdpyinfo(kotlinExtensions).contains("XInputExtension")}")
            appendLine("xvfbXInputWarning=${xInputWarning(xvfbTrace)}")
            appendLine("kotlinXInputWarning=${xInputWarning(kotlinTrace)}")
            appendLine("xvfbRuntimeAgentLoaded=${propertyValue(xvfbRuntime, "agentLoaded")}")
            appendLine("kotlinRuntimeAgentLoaded=${propertyValue(kotlinRuntime, "agentLoaded")}")
            appendLine("xvfbRuntimeMainMenuDisplayMode=${propertyValue(xvfbRuntime, "runtimeMainMenuDisplayMode")}")
            appendLine("kotlinRuntimeMainMenuDisplayMode=${propertyValue(kotlinRuntime, "runtimeMainMenuDisplayMode")}")
            appendLine("xvfbRuntimeStateMainMenuDisplayMode=${propertyValue(xvfbRuntime, "runtimeStateMainMenuDisplayMode")}")
            appendLine("kotlinRuntimeStateMainMenuDisplayMode=${propertyValue(kotlinRuntime, "runtimeStateMainMenuDisplayMode")}")
            appendLine("xvfbRuntimeShadowStateMainMenuDisplayMode=${propertyValue(xvfbRuntime, "runtimeShadowStateMainMenuDisplayMode")}")
            appendLine("kotlinRuntimeShadowStateMainMenuDisplayMode=${propertyValue(kotlinRuntime, "runtimeShadowStateMainMenuDisplayMode")}")
            appendLine("xvfbRuntimeMainMenuDisplayModePrev=${propertyValue(xvfbRuntime, "runtimeMainMenuDisplayModePrev")}")
            appendLine("kotlinRuntimeMainMenuDisplayModePrev=${propertyValue(kotlinRuntime, "runtimeMainMenuDisplayModePrev")}")
            appendLine("xvfbRuntimeShowMainMenu=${propertyValue(xvfbRuntime, "runtimeShowMainMenu")}")
            appendLine("kotlinRuntimeShowMainMenu=${propertyValue(kotlinRuntime, "runtimeShowMainMenu")}")
            appendLine("xvfbRuntimeStateShowMainMenu=${propertyValue(xvfbRuntime, "runtimeStateShowMainMenu")}")
            appendLine("kotlinRuntimeStateShowMainMenu=${propertyValue(kotlinRuntime, "runtimeStateShowMainMenu")}")
            appendLine("xvfbRuntimeShadowStateShowMainMenu=${propertyValue(xvfbRuntime, "runtimeShadowStateShowMainMenu")}")
            appendLine("kotlinRuntimeShadowStateShowMainMenu=${propertyValue(kotlinRuntime, "runtimeShadowStateShowMainMenu")}")
            appendLine("xvfbRuntimeShowMainToolbar=${propertyValue(xvfbRuntime, "runtimeShowMainToolbar")}")
            appendLine("kotlinRuntimeShowMainToolbar=${propertyValue(kotlinRuntime, "runtimeShowMainToolbar")}")
            appendLine("xvfbRuntimeStateModificationCount=${propertyValue(xvfbRuntime, "runtimeStateModificationCount")}")
            appendLine("kotlinRuntimeStateModificationCount=${propertyValue(kotlinRuntime, "runtimeStateModificationCount")}")
            appendLine("xvfbRuntimeSettingsIdentity=${propertyValue(xvfbRuntime, "runtimeSettingsIdentity")}")
            appendLine("kotlinRuntimeSettingsIdentity=${propertyValue(kotlinRuntime, "runtimeSettingsIdentity")}")
            appendLine("xvfbRuntimeStateIdentity=${propertyValue(xvfbRuntime, "runtimeStateIdentity")}")
            appendLine("kotlinRuntimeStateIdentity=${propertyValue(kotlinRuntime, "runtimeStateIdentity")}")
            appendLine("xvfbRuntimeMenuButtonInToolbar=${propertyValue(xvfbRuntime, "runtimeMenuButtonInToolbar")}")
            appendLine("kotlinRuntimeMenuButtonInToolbar=${propertyValue(kotlinRuntime, "runtimeMenuButtonInToolbar")}")
            appendLine("xvfbRuntimeHideNativeLinuxTitleNotSupportedReason=${propertyValue(xvfbRuntime, "runtimeHideNativeLinuxTitleNotSupportedReason")}")
            appendLine("kotlinRuntimeHideNativeLinuxTitleNotSupportedReason=${propertyValue(kotlinRuntime, "runtimeHideNativeLinuxTitleNotSupportedReason")}")
            appendLine("xvfbRuntimeJbrWindowMoveSupported=${propertyValue(xvfbRuntime, "runtimeJbrWindowMoveSupported")}")
            appendLine("kotlinRuntimeJbrWindowMoveSupported=${propertyValue(kotlinRuntime, "runtimeJbrWindowMoveSupported")}")
            appendLine("xvfbRuntimeStartupIsXToolkit=${propertyValue(xvfbRuntime, "runtimeStartupIsXToolkit")}")
            appendLine("kotlinRuntimeStartupIsXToolkit=${propertyValue(kotlinRuntime, "runtimeStartupIsXToolkit")}")
            appendLine("xvfbUiDecisionLines=${intellijUiDecisionLines(xvfbTrace).joinToString(" | ").ifEmpty { "None" }}")
            appendLine("kotlinUiDecisionLines=${intellijUiDecisionLines(kotlinTrace).joinToString(" | ").ifEmpty { "None" }}")
        }
    }

    private fun envValue(text: String, name: String): String =
        Regex("""(?m)^${Regex.escape(name)}=(.*)$""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: "<missing>"

    private fun propertyValue(text: String, name: String): String =
        Regex("""(?m)^${Regex.escape(name)}=(.*)$""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: "<missing>"

    private fun xmlOptionValue(xml: String, name: String): String =
        Regex("""<option\b[^>]*\bname="${Regex.escape(name)}"[^>]*\bvalue="([^"]+)"""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?: Regex("""<option\b[^>]*\bvalue="([^"]+)"[^>]*\bname="${Regex.escape(name)}"""")
                .find(xml)
                ?.groupValues
                ?.get(1)
            ?: "<missing>"

    private fun xInputWarning(text: String): Boolean =
        Regex("""X Input extension.*(?:isnt|isn't|not).*available""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun intellijUiDecisionLines(text: String): List<String> {
        val needles = listOf(
            "mainmenudisplaymode",
            "main menu",
            "customwindowheader",
            "native linux title",
            "hide native",
            "x11uiutil",
            "tilewm",
            "undefineddesktop",
            "_net protocol",
            "window move",
        )
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                val lower = line.lowercase()
                needles.any { lower.contains(it) }
            }
            .distinct()
            .take(40)
            .toList()
    }

    private fun angleInitializationPbufferFailure(text: String): Boolean =
        text.contains("Could not create the initialization pbuffer")

    private fun angleMissingEsProfileMessage(text: String): Boolean =
        text.contains("Cannot create an OpenGL ES platform on GLX without the GLX_EXT_create_context_es_profile extension")

    private fun angleNoMatchingFbConfigsOrVisuals(text: String): Boolean =
        text.contains("No matching fbConfigs or visuals found")

    private fun angleEs2NotSupportable(text: String): Boolean =
        text.contains("OpenGL ES 2.0 is not supportable")

    private fun angleMissingArbCreateContextMessage(text: String): Boolean =
        text.contains("Cannot create an OpenGL ES platform on GLX without the GLX_ARB_create_context extension")

    private fun angleFailureSignatures(text: String): List<String> =
        listOfNotNull(
            "initialization-pbuffer".takeIf { angleInitializationPbufferFailure(text) },
            "missing-es-profile".takeIf { angleMissingEsProfileMessage(text) },
            "no-matching-fbconfigs-or-visuals".takeIf { angleNoMatchingFbConfigsOrVisuals(text) },
            "es2-not-supportable".takeIf { angleEs2NotSupportable(text) },
            "missing-arb-create-context".takeIf { angleMissingArbCreateContextMessage(text) },
        )

    private fun listedExtensionsFromXdpyinfo(text: String): List<String> {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim().startsWith("number of extensions:") }
        if (start < 0) return emptyList()
        return lines
            .asSequence()
            .drop(start + 1)
            .takeWhile { line -> line.isBlank() || line.firstOrNull()?.isWhitespace() == true }
            .map { it.trim() }
            .map { line -> Regex("""\s+\(opcode:.*$""").replace(line, "") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun xdpyinfoGlxDetailUnsupported(text: String): Boolean =
        text.contains("GLX extension not supported by xdpyinfo")

    private fun glxExtensionsFromXdpyinfo(text: String): List<String> {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim() == "GLX extensions:" }
        if (start < 0) return emptyList()
        return lines
            .asSequence()
            .drop(start + 1)
            .takeWhile { line -> line.isBlank() || line.firstOrNull()?.isWhitespace() == true }
            .flatMap { line -> line.trim().split(Regex("""[,\s]+""")).asSequence() }
            .filter { it.startsWith("GLX_") }
            .distinct()
            .sorted()
            .toList()
    }

    private fun clientGlxExtensionsFromText(text: String): List<String> =
        Regex("""\bglxExtensions=([^\n]*)""")
            .findAll(text)
            .flatMap { match -> match.groupValues[1].trim().split(Regex("""\s+""")).asSequence() }
            .filter { it.startsWith("GLX_") }
            .distinct()
            .sorted()
            .toList()

    private fun serverGlxExtensionsFromText(text: String): List<String> =
        Regex("""\bQuery(?:ServerString|ExtensionsString)\s+minor=\d+[^\n]*\bvalue=([^\n]*)""")
            .findAll(text)
            .flatMap { match -> match.groupValues[1].trim().split(Regex("""\s+""")).asSequence() }
            .filter { it.startsWith("GLX_") }
            .distinct()
            .sorted()
            .toList()

    private fun glxLifecycleOperationLinesFromText(text: String): List<String> {
        val lifecycleOperations = setOf(
            "CreateContext",
            "CreateNewContext",
            "CreateContextAttribsARB",
            "CreatePbuffer",
            "DestroyContext",
            "DestroyPbuffer",
            "GetDrawableAttributes",
            "MakeCurrent",
            "MakeContextCurrent",
            "QueryContext",
        )
        return Regex("""-\s+#\d+\s+([A-Za-z0-9]+)\s+minor=\d+[^\n]*""")
            .findAll(text)
            .filter { match -> match.groupValues[1] in lifecycleOperations }
            .map { match -> match.value.trim() }
            .distinct()
            .toList()
    }

    private fun glxLifecycleOperationNamesFromLines(lines: List<String>): List<String> =
        lines
            .mapNotNull { line -> Regex("""#\d+\s+([A-Za-z0-9]+)\s+minor=""").find(line)?.groupValues?.get(1) }
            .distinct()
            .sorted()

    private fun glxProtocolErrorLinesFromText(text: String): List<String> =
        Regex("""-\s+#\d+\s+Error\s+minor=\d+[^\n]*""")
            .findAll(text)
            .map { match -> match.value.trim() }
            .distinct()
            .toList()

    private fun intellijLogArtifactName(prefix: String, path: String): String {
        val normalized = path.substringAfterLast('/')
            .removeSuffix(".log")
            .replace('_', '-')
            .replace(Regex("""[^A-Za-z0-9.-]+"""), "-")
            .trim('-')
        return "$prefix-$normalized.log"
    }

    private fun svgLayerInventory(layers: List<SvgLayer>): String =
        buildString {
            appendLine("count=${layers.size}")
            layers.forEachIndexed { index, layer ->
                append(index)
                append(": id=").append(layer.id)
                append(" source=").append(layer.source)
                append(" x=").append(layer.x)
                append(" y=").append(layer.y)
                append(" width=").append(layer.width)
                append(" height=").append(layer.height)
                append(" type=").append(if (layer.bytes != null) "png" else "fill")
                append(" clipRectangles=").append(layer.clipRectangles.size)
                if (layer.fill != null) append(" fill=0x").append(layer.fill.toUInt().toString(16))
                appendLine()
            }
        }

    private fun htmlPreviewInventory(previews: List<HtmlPreviewSurface>): String =
        buildString {
            appendLine("count=${previews.size}")
            previews.forEachIndexed { index, preview ->
                append(index)
                append(": label=").append(preview.label)
                append(" view=").append(preview.viewWidth).append('x').append(preview.viewHeight)
                append(" window=").append(preview.windowId)
                append(" source=").append(preview.source)
                preview.pixmapId?.let { append(" pixmap=").append(it) }
                preview.pictureId?.let { append(" picture=").append(it) }
                append(" x=").append(preview.x)
                append(" y=").append(preview.y)
                append(" width=").append(preview.width)
                append(" height=").append(preview.height)
                appendLine()
            }
        }

    private fun dumpIntellijVisualDiff(
        directory: File,
        prefix: String,
        expected: VisualCapture,
        actual: VisualCapture,
    ) {
        ImageIO.write(visualDiffImage(expected.image, actual.image), "png", File(directory, "$prefix-diff.png"))
        File(directory, "$prefix-metrics.txt").writeText(
            buildString {
                appendLine("expected=$expected")
                appendLine("actual=$actual")
                appendLine("coverageRatio=${ratio(actual.nonWhitePixels, expected.nonWhitePixels)}")
                appendLine("averageRgbDelta=${abs(actual.averageRgb - expected.averageRgb)}")
                appendLine("sampledDistance=${imageDistance(expected.image, actual.image)}")
            },
        )
    }

    private fun intellijVisualRegionMetrics(
        text: String,
        expected: VisualCapture,
        actualRobot: VisualCapture,
        actualSvg: VisualCapture,
    ): String {
        val frame = largestMappedRootChildWindow(text)
        return buildString {
            appendLine("screen=${expected.width}x${expected.height}")
            appendLine("expectedFullScreen=$expected")
            appendLine("robotFullScreen=$actualRobot")
            appendLine("svgFullScreen=$actualSvg")
            if (frame == null) {
                appendLine("ideaFrame=none")
                return@buildString
            }
            appendLine("ideaFrame=${frame.x},${frame.y} ${frame.width}x${frame.height}")
            appendLine("ideaFrameArea=${frame.width * frame.height}")
            appendLine("topMargin=${frame.y}")
            appendLine("leftMargin=${frame.x}")
            appendLine("rightMargin=${(expected.width - frame.x - frame.width).coerceAtLeast(0)}")
            appendLine("bottomMargin=${(expected.height - frame.y - frame.height).coerceAtLeast(0)}")
            appendLine("expectedInsideFrame=${regionCapture(expected.image, frame)}")
            appendLine("robotInsideFrame=${regionCapture(actualRobot.image, frame)}")
            appendLine("svgInsideFrame=${regionCapture(actualSvg.image, frame)}")
            appendLine("expectedOutsideFrameNonWhitePixels=${outsideRegionNonWhitePixels(expected.image, frame)}")
            appendLine("robotOutsideFrameNonWhitePixels=${outsideRegionNonWhitePixels(actualRobot.image, frame)}")
            appendLine("svgOutsideFrameNonWhitePixels=${outsideRegionNonWhitePixels(actualSvg.image, frame)}")
            appendLine(
                "robotInsideFrameCoverageRatio=${
                    ratio(
                        regionCapture(actualRobot.image, frame).nonWhitePixels,
                        regionCapture(expected.image, frame).nonWhitePixels,
                    )
                }",
            )
            appendLine(
                "svgInsideFrameCoverageRatio=${
                    ratio(
                        regionCapture(actualSvg.image, frame).nonWhitePixels,
                        regionCapture(expected.image, frame).nonWhitePixels,
                    )
                }",
            )
            appendLine("robotInsideFrameSampledDistance=${imageDistance(regionImage(expected.image, frame), regionImage(actualRobot.image, frame))}")
            appendLine("svgInsideFrameSampledDistance=${imageDistance(regionImage(expected.image, frame), regionImage(actualSvg.image, frame))}")
            appendLine("robotVsSvgSampledDistance=${imageDistance(actualRobot.image, actualSvg.image)}")
            appendLine("robotVsSvgInsideFrameSampledDistance=${imageDistance(regionImage(actualRobot.image, frame), regionImage(actualSvg.image, frame))}")
            appendLine("robotMismatchBounds=${mismatchBounds(expected.image, actualRobot.image).toMetricString()}")
            appendLine("svgMismatchBounds=${mismatchBounds(expected.image, actualSvg.image).toMetricString()}")
            appendLine("robotVsSvgMismatchBounds=${mismatchBounds(actualRobot.image, actualSvg.image).toMetricString()}")
            appendLine("robotInsideFrameMismatchBounds=${mismatchBounds(regionImage(expected.image, frame), regionImage(actualRobot.image, frame)).toMetricString()}")
            appendLine("svgInsideFrameMismatchBounds=${mismatchBounds(regionImage(expected.image, frame), regionImage(actualSvg.image, frame)).toMetricString()}")
            appendLine("robotVsSvgInsideFrameMismatchBounds=${mismatchBounds(regionImage(actualRobot.image, frame), regionImage(actualSvg.image, frame)).toMetricString()}")
            appendIntellijRegionComparison("topFrameBand", frame.topBand(120), expected, actualRobot, actualSvg)
            appendIntellijRegionComparison("rightFrameBand", frame.rightBand(96), expected, actualRobot, actualSvg)
            appendIntellijRegionComparison("bottomFrameBand", frame.bottomBand(96), expected, actualRobot, actualSvg)
        }
    }

    private fun StringBuilder.appendIntellijRegionComparison(
        name: String,
        region: Rectangle,
        expected: VisualCapture,
        actualRobot: VisualCapture,
        actualSvg: VisualCapture,
    ) {
        val expectedRegion = regionImage(expected.image, region)
        val robotRegion = regionImage(actualRobot.image, region)
        val svgRegion = regionImage(actualSvg.image, region)
        val metricPrefix = name.replaceFirstChar { it.uppercaseChar() }
        appendLine("$name=${region.x},${region.y} ${region.width}x${region.height}")
        appendLine("robot${metricPrefix}SampledDistance=${imageDistance(expectedRegion, robotRegion)}")
        appendLine("svg${metricPrefix}SampledDistance=${imageDistance(expectedRegion, svgRegion)}")
        appendLine("robotVsSvg${metricPrefix}SampledDistance=${imageDistance(robotRegion, svgRegion)}")
        appendLine("robot${metricPrefix}MismatchBounds=${mismatchBounds(expectedRegion, robotRegion).toMetricString()}")
        appendLine("svg${metricPrefix}MismatchBounds=${mismatchBounds(expectedRegion, svgRegion).toMetricString()}")
        appendLine("robotVsSvg${metricPrefix}MismatchBounds=${mismatchBounds(robotRegion, svgRegion).toMetricString()}")
    }

    private fun Rectangle.topBand(maxHeight: Int): Rectangle =
        Rectangle(x, y, width, height.coerceAtMost(maxHeight).coerceAtLeast(1))

    private fun Rectangle.rightBand(maxWidth: Int): Rectangle {
        val bandWidth = width.coerceAtMost(maxWidth).coerceAtLeast(1)
        return Rectangle(x + width - bandWidth, y, bandWidth, height)
    }

    private fun Rectangle.bottomBand(maxHeight: Int): Rectangle {
        val bandHeight = height.coerceAtMost(maxHeight).coerceAtLeast(1)
        return Rectangle(x, y + height - bandHeight, width, bandHeight)
    }

    private fun largestMappedRootChildWindow(text: String): Rectangle? {
        val rootId = Regex("""-\s+(0x[0-9a-fA-F]+)\s+parent=\S+\s+label="root"""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: return null
        return Regex(
            """-\s+0x[0-9a-fA-F]+\s+parent=${Regex.escape(rootId)}\b[^\n]*\bgeometry=(-?\d+),(-?\d+)\s+(\d+)x(\d+)[^\n]*\bmapped=true\b""",
        )
            .findAll(text)
            .map { match ->
                Rectangle(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                    match.groupValues[4].toInt(),
                )
            }
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun regionCapture(image: BufferedImage, region: Rectangle): VisualCapture =
        visualCapture(regionImage(image, region))

    private fun regionImage(image: BufferedImage, region: Rectangle): BufferedImage {
        val x = region.x.coerceIn(0, image.width)
        val y = region.y.coerceIn(0, image.height)
        val right = (region.x + region.width).coerceIn(0, image.width)
        val bottom = (region.y + region.height).coerceIn(0, image.height)
        val width = (right - x).coerceAtLeast(1)
        val height = (bottom - y).coerceAtLeast(1)
        val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = copy.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, width, height, x, y, x + width, y + height, null)
        } finally {
            graphics.dispose()
        }
        return copy
    }

    private fun outsideRegionNonWhitePixels(image: BufferedImage, region: Rectangle): Int {
        var nonWhite = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (region.contains(x, y)) continue
                val argb = image.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xff
                val rgb = argb and 0x00ff_ffff
                if (alpha > 0 && rgb != 0x00ff_ffff) nonWhite++
            }
        }
        return nonWhite
    }

    private fun mismatchBounds(expected: BufferedImage, actual: BufferedImage): Rectangle? {
        val width = minOf(expected.width, actual.width)
        val height = minOf(expected.height, actual.height)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (rgbDistance(expected.getRGB(x, y), actual.getRGB(x, y)) == 0) continue
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        return if (maxX < minX || maxY < minY) null else Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun Rectangle?.toMetricString(): String =
        this?.let { "${it.x},${it.y} ${it.width}x${it.height}" } ?: "none"

    private fun intellijRenderBandSection(text: String, band: String): String {
        val header = "RENDER operations intersecting $band mapped root-child band:"
        val start = text.indexOf(header)
        if (start < 0) return ""
        val nextBand = Regex("""\nRENDER operations intersecting (top|right|bottom) mapped root-child band:""")
            .find(text, start + header.length)
            ?.range
            ?.first
        val nextSection = text.indexOf("\nRecent PutImage commands:", start).takeIf { it >= 0 }
        val end = listOfNotNull(nextBand, nextSection).minOrNull() ?: text.length
        return text.substring(start, end).trimEnd() + "\n"
    }

    private fun visualDiffImage(expected: BufferedImage, actual: BufferedImage): BufferedImage {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedRgb = if (x < expected.width && y < expected.height) expected.getRGB(x, y) else 0
                val actualRgb = if (x < actual.width && y < actual.height) actual.getRGB(x, y) else 0
                val red = (abs(((expectedRgb ushr 16) and 0xff) - ((actualRgb ushr 16) and 0xff)) * 4).coerceAtMost(255)
                val green = (abs(((expectedRgb ushr 8) and 0xff) - ((actualRgb ushr 8) and 0xff)) * 4).coerceAtMost(255)
                val blue = (abs((expectedRgb and 0xff) - (actualRgb and 0xff)) * 4).coerceAtMost(255)
                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
        }
        return image
    }

    private fun ratio(numerator: Int, denominator: Int): String =
        if (denominator == 0) "n/a" else (numerator.toDouble() / denominator.toDouble()).toString()

    private fun visualCapture(stdout: String): VisualCapture {
        val encoded = stdout.lineSequence()
            .firstOrNull { it.startsWith("PNG_BASE64=") }
            ?.removePrefix("PNG_BASE64=")
            ?: error("XIntellijRobotCapture did not print PNG_BASE64, stdout:\n$stdout")
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            ?: error("XIntellijRobotCapture PNG was not readable")
        return visualCapture(image)
    }

    private fun visualCapture(image: BufferedImage): VisualCapture {
        var nonWhite = 0
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                redSum += (argb ushr 16) and 0xff
                greenSum += (argb ushr 8) and 0xff
                blueSum += argb and 0xff
                val alpha = (argb ushr 24) and 0xff
                val rgb = argb and 0x00ff_ffff
                if (alpha > 0 && rgb != 0x00ff_ffff) nonWhite++
            }
        }
        val pixels = image.width * image.height
        return VisualCapture(
            width = image.width,
            height = image.height,
            nonWhitePixels = nonWhite,
            averageRgb = (redSum + greenSum + blueSum).toDouble() / (pixels * 3.0 * 255.0),
            image = image,
        )
    }

    private fun solidImage(width: Int, height: Int, argb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = java.awt.Color(argb, true)
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
        }

    private fun svgWithRootImage(image: BufferedImage): String {
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
        return """
            <svg viewBox="0 0 ${image.width} ${image.height}">
              <image class="framebuffer-image screen-framebuffer-image" data-source="composited-root" data-window-id="0x26" x="0" y="0" width="${image.width}" height="${image.height}" href="data:image/png;base64,$encoded"/>
            </svg>
        """.trimIndent()
    }

    private fun assertIntellijVisualClose(expected: VisualCapture, actual: VisualCapture, label: String) {
        assertEquals(expected.width, actual.width, "$label width should match Xvfb reference")
        assertEquals(expected.height, actual.height, "$label height should match Xvfb reference")
        assertClose(
            expected = expected.nonWhitePixels,
            actual = actual.nonWhitePixels,
            tolerance = 0.35,
            message = "$label should expose similar non-white coverage to Xvfb; reference=$expected actual=$actual",
        )
        assertClose(
            expected = expected.averageRgb,
            actual = actual.averageRgb,
            tolerance = 0.16,
            message = "$label should expose similar average RGB to Xvfb; reference=$expected actual=$actual",
        )
        val distance = imageDistance(expected.image, actual.image)
        assertTrue(
            distance <= 125.0,
            "$label should stay roughly close to Xvfb reference; distance=$distance\nreference=$expected\nactual=$actual",
        )
    }

    private fun assertClose(expected: Int, actual: Int, tolerance: Double, message: String) {
        val allowed = (expected * tolerance).toInt().coerceAtLeast(1)
        assertTrue(abs(expected - actual) <= allowed, message)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, message)
    }

    private fun imageDistance(reference: BufferedImage, actual: BufferedImage): Double {
        var total = 0L
        var samples = 0
        for (y in 0 until reference.height step 5) {
            for (x in 0 until reference.width step 5) {
                total += rgbDistance(reference.getRGB(x, y), actual.getRGB(x, y))
                samples++
            }
        }
        return total.toDouble() / samples.toDouble()
    }

    private fun fullImageDistance(reference: BufferedImage, actual: BufferedImage): Double {
        assertEquals(reference.width, actual.width, "image width should match")
        assertEquals(reference.height, actual.height, "image height should match")
        var total = 0L
        val pixels = reference.width * reference.height
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                total += rgbDistance(reference.getRGB(x, y), actual.getRGB(x, y))
            }
        }
        return total.toDouble() / pixels.toDouble()
    }

    private fun rgbDistance(left: Int, right: Int): Int =
        abs(((left ushr 16) and 0xff) - ((right ushr 16) and 0xff)) +
            abs(((left ushr 8) and 0xff) - ((right ushr 8) and 0xff)) +
            abs((left and 0xff) - (right and 0xff))

    private data class EmbeddedPng(
        val id: String,
        val bytes: ByteArray,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private data class VisualSnapshot(
        val stats: List<ImageStats>,
        val text: String,
    )

    private data class IntellijReferenceCapture(
        val robot: VisualCapture,
        val logs: List<IntellijLogArtifact>,
    )

    private data class IntellijKotlinCapture(
        val robot: VisualCapture,
        val text: String,
        val stateJson: String,
        val svg: String,
        val html: String,
        val svgLayers: List<SvgLayer>,
        val logs: List<IntellijLogArtifact>,
    )

    private data class IntellijLogArtifact(
        val fileName: String,
        val text: String,
    )

    private data class IntellijParityReadiness(
        val ready: Boolean,
        val missing: List<String>,
    )

    private data class SvgLayer(
        val id: String,
        val source: String,
        val bytes: ByteArray?,
        val fill: Int?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val clipRectangles: List<Rectangle>,
    )

    private data class HtmlPreviewSurface(
        val label: String,
        val viewWidth: Int,
        val viewHeight: Int,
        val windowId: String,
        val pixmapId: String?,
        val pictureId: String?,
        val source: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private data class VisualCapture(
        val width: Int,
        val height: Int,
        val nonWhitePixels: Int,
        val averageRgb: Double,
        val image: BufferedImage,
    ) {
        override fun toString(): String =
            "VisualCapture(width=$width, height=$height, nonWhitePixels=$nonWhitePixels, averageRgb=$averageRgb)"
    }

    private data class ImageStats(
        val id: String,
        val width: Int,
        val height: Int,
        val nonWhitePixels: Int,
        val distinctNonWhiteColors: Int,
        val samples: List<String>,
    ) {
        fun hasVisibleContent(): Boolean =
            nonWhitePixels > 100 && distinctNonWhiteColors >= 3

        fun hasLargeVisibleSurface(): Boolean =
            width >= 640 &&
                height >= 360 &&
                nonWhitePixels > 10_000 &&
                distinctNonWhiteColors >= 16
    }

    private companion object {
        const val IntellijCaptureWidth = 1280
        const val IntellijCaptureHeight = 900
        const val IntellijOpenWaitSeconds = 300
        const val IntellijParityReadyWaitSeconds = 240
        const val IntellijContainerCommandTimeoutSeconds = 900
    }
}
