package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwtPrimitiveDockerTest {
    @Test
    fun `awt primitives render through xrender pipeline into framebuffer`() {
        val result = runAwtProbe(
            port = 6210,
            title = "AWT Primitive Probe",
            mainClass = "AwtPrimitiveProbe",
            source = AwtPrimitiveProbeSource,
            readinessText = "RENDER.Composite:",
        )

        assertContains(result.text, "RENDER supported=true")
        assertContains(result.text, "PutImage:")
        assertContains(result.text, "RENDER.")
        assertTrue(result.svg.hasSvgClass("framebuffer-image"), "Expected framebuffer-image class in SVG")
        assertContains(result.svg, """href="data:image/png;base64,""")
        assertContains(result.html, "AWT Primitive Probe")

        val paintedPixels = result.stats.maxOf { it.nonWhitePixels }
        assertTrue(paintedPixels > 100, "AWT framebuffer should contain non-white painted pixels, got ${result.stats}\n${result.text}\n${result.log}")
    }

    @Test
    fun `awt buffer strategy presents offscreen frame into visible framebuffer`() {
        val result = runAwtProbe(
            port = 6211,
            title = "AWT BufferStrategy Probe",
            mainClass = "AwtBufferStrategyProbe",
            source = AwtBufferStrategyProbeSource,
            readinessText = "RENDER.Composite:",
        )

        assertContains(result.text, "AWT BufferStrategy Probe")
        assertContains(result.text, "CreatePixmap:")
        assertContains(result.text, "RENDER.")
        val paintedPixels = result.stats.maxOf { it.nonWhitePixels }
        assertTrue(paintedPixels > 100, "BufferStrategy frame should be presented into a visible window, got ${result.stats}\n${result.text}\n${result.log}")
    }

    @Test
    fun `awt text glyphs render into visible framebuffer`() {
        val result = runAwtProbe(
            port = 6212,
            title = "AWT Text Probe",
            mainClass = "AwtTextProbe",
            source = AwtTextProbeSource,
            readinessText = "RENDER.CompositeGlyphs32:",
        )

        assertContains(result.text, "RENDER.CompositeGlyphs32:")
        val paintedPixels = result.stats.maxOf { it.nonWhitePixels }
        assertTrue(paintedPixels > 100, "Text-only AWT frame should contain glyph pixels, got ${result.stats}\n${result.text}\n${result.log}")
    }

    @Test
    fun `awt robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualParityProbe",
            source = VisualParityProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6213,
            title = "AWT Visual Parity Probe",
            mainClass = "VisualParityProbe",
            source = VisualParityProbeSource,
        )

        assertContains(actual.text, "AWT Visual Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain a framebuffer image for the visual parity probe")

        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin Robot screenshot",
        )
        val exportedFramebuffer = actual.exportedFramebuffers
            .filter { it.width == reference.width && it.height == reference.height }
            .minByOrNull { imageDistance(reference.image, it.image) }
            ?: error("Kotlin SVG export did not contain a framebuffer matching the Xvfb capture dimensions ${reference.width}x${reference.height}; exported=${actual.exportedFramebuffers}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = exportedFramebuffer,
            label = "Kotlin SVG exported framebuffer",
        )
    }

    @Test
    fun `awt overlapping windows robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualOverlapProbe",
            source = VisualOverlapProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6214,
            title = "AWT Overlap Parity Probe",
            mainClass = "VisualOverlapProbe",
            source = VisualOverlapProbeSource,
        )

        assertContains(actual.text, "AWT Overlap Parity Probe")
        assertContains(actual.text, "overlaps")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the overlapping window probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 } &&
                actual.exportedFramebuffers.any { it.width == 230 && it.height == 165 },
            "Overlapping Java windows should expose framebuffer-backed surfaces for both top-level windows; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin overlapping-window Robot screenshot",
        )
    }

    @Test
    fun `awt owned popup robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualPopupProbe",
            source = VisualPopupProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6215,
            title = "AWT Popup Parity Probe",
            mainClass = "VisualPopupProbe",
            source = VisualPopupProbeSource,
        )

        assertContains(actual.text, "AWT Popup Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the owned popup probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 } &&
                actual.exportedFramebuffers.any { it.width == 210 && it.height == 150 },
            "Owned popup windows should expose framebuffer-backed surfaces for both owner and popup; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin owned-popup Robot screenshot",
        )
    }

    @Test
    fun `awt owned dialog robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualDialogProbe",
            source = VisualDialogProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6216,
            title = "AWT Dialog Parity Probe",
            mainClass = "VisualDialogProbe",
            source = VisualDialogProbeSource,
        )

        assertContains(actual.text, "AWT Dialog Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the owned dialog probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 } &&
                actual.exportedFramebuffers.any { it.width == 240 && it.height == 170 },
            "Owned dialog windows should expose framebuffer-backed surfaces for both owner and dialog; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin owned-dialog Robot screenshot",
        )
    }

    @Test
    fun `awt heavyweight popup menu robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualPopupMenuProbe",
            source = VisualPopupMenuProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6217,
            title = "AWT PopupMenu Parity Probe",
            mainClass = "VisualPopupMenuProbe",
            source = VisualPopupMenuProbeSource,
        )

        assertContains(actual.text, "AWT PopupMenu Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the heavyweight popup menu probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 } &&
                actual.exportedFramebuffers.any { it.width == 220 && it.height == 150 },
            "Heavyweight popup menu windows should expose framebuffer-backed surfaces for both owner and menu; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin heavyweight-popup-menu Robot screenshot",
        )
    }

    @Test
    fun `awt menu dropdown robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualMenuDropdownProbe",
            source = VisualMenuDropdownProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6218,
            title = "AWT Menu Dropdown Parity Probe",
            mainClass = "VisualMenuDropdownProbe",
            source = VisualMenuDropdownProbeSource,
        )

        assertContains(actual.text, "AWT Menu Dropdown Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the menu dropdown probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 } &&
                actual.exportedFramebuffers.any { it.width == 230 && it.height == 140 },
            "Menu dropdown windows should expose framebuffer-backed surfaces for both owner and dropdown; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin menu-dropdown Robot screenshot",
        )
    }

    @Test
    fun `awt combo dropdown robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualComboDropdownProbe",
            source = VisualComboDropdownProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6219,
            title = "AWT Combo Dropdown Parity Probe",
            mainClass = "VisualComboDropdownProbe",
            source = VisualComboDropdownProbeSource,
        )

        assertContains(actual.text, "AWT Combo Dropdown Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertContains(actual.text, "geometry=118,132 204x114")
        assertContains(actual.text, "overrideRedirect=true")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the combo dropdown probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 },
            "Combo dropdown rendering should expose the owner framebuffer surface; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin combo-dropdown Robot screenshot",
        )
    }

    @Test
    fun `awt tooltip popup robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualTooltipProbe",
            source = VisualTooltipProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6220,
            title = "AWT Tooltip Parity Probe",
            mainClass = "VisualTooltipProbe",
            source = VisualTooltipProbeSource,
        )

        assertContains(actual.text, "AWT Tooltip Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertContains(actual.text, """label="sun-awt-X11-XPanelPeer" geometry=154,136 190x82""")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the tooltip probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 } &&
                actual.exportedFramebuffers.any { it.width in 188..192 && it.height in 80..84 },
            "Tooltip popup rendering should expose framebuffer surfaces for both owner and tooltip; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin tooltip-popup Robot screenshot",
        )
    }

    @Test
    fun `awt dense swing content robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualDenseSwingProbe",
            source = VisualDenseSwingProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6221,
            title = "AWT Dense Swing Parity Probe",
            mainClass = "VisualDenseSwingProbe",
            source = VisualDenseSwingProbeSource,
        )

        assertContains(actual.text, "AWT Dense Swing Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the dense Swing probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 },
            "Dense Swing rendering should expose the owner framebuffer surface; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin dense-Swing Robot screenshot",
        )
    }

    @Test
    fun `awt swing form controls robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualFormControlsProbe",
            source = VisualFormControlsProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6222,
            title = "AWT Form Controls Parity Probe",
            mainClass = "VisualFormControlsProbe",
            source = VisualFormControlsProbeSource,
        )

        assertContains(actual.text, "AWT Form Controls Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the Swing form-controls probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 },
            "Swing form controls should expose the owner framebuffer surface; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin Swing form-controls Robot screenshot",
        )
    }

    @Test
    fun `awt tabbed split pane robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualTabbedSplitPaneProbe",
            source = VisualTabbedSplitPaneProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6223,
            title = "AWT Tabbed Split Pane Parity Probe",
            mainClass = "VisualTabbedSplitPaneProbe",
            source = VisualTabbedSplitPaneProbeSource,
        )

        assertContains(actual.text, "AWT Tabbed Split Pane Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the tabbed split-pane probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 },
            "Tabbed split-pane Swing rendering should expose the owner framebuffer surface; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin tabbed split-pane Robot screenshot",
        )
        assertExportedFramebufferClose(
            expected = reference,
            exportedFramebuffers = actual.exportedFramebuffers,
            label = "Kotlin tabbed split-pane SVG exported framebuffer",
        )
    }

    @Test
    fun `awt desktop pane internal frames robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualDesktopPaneProbe",
            source = VisualDesktopPaneProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6224,
            title = "AWT Desktop Pane Parity Probe",
            mainClass = "VisualDesktopPaneProbe",
            source = VisualDesktopPaneProbeSource,
        )

        assertContains(actual.text, "AWT Desktop Pane Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the desktop-pane probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 },
            "Desktop-pane Swing rendering should expose the owner framebuffer surface; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin desktop-pane Robot screenshot",
        )
    }

    @Test
    fun `awt layered pane glass overlay robot screenshot roughly matches xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runRobotProbeAgainstXvfb(
            mainClass = "VisualLayeredOverlayProbe",
            source = VisualLayeredOverlayProbeSource,
        )
        val actual = runRobotProbeAgainstKotlinServer(
            port = 6225,
            title = "AWT Layered Overlay Parity Probe",
            mainClass = "VisualLayeredOverlayProbe",
            source = VisualLayeredOverlayProbeSource,
        )

        assertContains(actual.text, "AWT Layered Overlay Parity Probe")
        assertContains(actual.text, "RENDER.")
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected Kotlin SVG export to retain framebuffer images for the layered-overlay probe")
        assertTrue(
            actual.exportedFramebuffers.any { it.width == 360 && it.height == 240 },
            "Layered overlay Swing rendering should expose the owner framebuffer surface; exported=${actual.exportedFramebuffers}\n${actual.text}",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin layered-overlay Robot screenshot",
        )
    }

    private fun assertVisualCaptureClose(
        expected: VisualProbeCapture,
        actual: VisualProbeCapture,
        label: String,
    ) {
        assertEquals(expected.width, actual.width, "$label content width should match Xvfb reference")
        assertEquals(expected.height, actual.height, "$label content height should match Xvfb reference")
        assertClose(
            expected = expected.nonBackgroundPixels,
            actual = actual.nonBackgroundPixels,
            tolerance = 0.12,
            message = "$label should expose similar non-background coverage to Xvfb; reference=$expected actual=$actual",
        )
        assertClose(
            expected = expected.averageRgb,
            actual = actual.averageRgb,
            tolerance = 0.08,
            message = "$label should expose similar average RGB to Xvfb; reference=$expected actual=$actual",
        )
        val sampleTolerance = 36
        for (point in VisualProbeSamplePoints) {
            val referencePixel = expected.sampleArgb.getValue(point)
            val actualPixel = actual.sampleArgb.getValue(point)
            assertTrue(
                rgbDistance(referencePixel, actualPixel) <= sampleTolerance,
                "$label sample $point differs too much from Xvfb: reference=${referencePixel.hexArgb()} actual=${actualPixel.hexArgb()}\nreference=$expected\nactual=$actual",
            )
        }
        assertTrue(
            imageDistance(expected.image, actual.image) <= 18.0,
            "$label should stay visually close to Xvfb reference\nreference=$expected\nactual=$actual",
        )
    }

    private fun assertExportedFramebufferClose(
        expected: VisualProbeCapture,
        exportedFramebuffers: List<VisualProbeCapture>,
        label: String,
    ) {
        val exportedFramebuffer = exportedFramebuffers
            .filter { it.width == expected.width && it.height == expected.height }
            .minByOrNull { imageDistance(expected.image, it.image) }
            ?: error("$label did not include a framebuffer matching the Xvfb capture dimensions ${expected.width}x${expected.height}; exported=$exportedFramebuffers")
        assertVisualCaptureClose(
            expected = expected,
            actual = exportedFramebuffer,
            label = label,
        )
    }

    private fun runAwtProbe(
        port: Int,
        title: String,
        mainClass: String,
        source: String,
        readinessText: String,
    ): ProbeResult {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()
                    val display = port - 6000
                    val sourceResult = container.execInContainer("sh", "-lc", "cat > /tmp/$mainClass.java <<'JAVA'\n$source\nJAVA\njavac /tmp/$mainClass.java")
                    assertEquals(0, sourceResult.exitCode, sourceResult.stderr + sourceResult.stdout)

                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        DISPLAY=host.docker.internal:$display \
                        nohup java -cp /tmp \
                          -Djava.awt.headless=false \
                          -Dsun.java2d.xrender=True \
                          -Dsun.java2d.opengl=false \
                          $mainClass >/tmp/awt-primitive.log 2>&1 &
                        echo ${'$'}! >/tmp/awt-primitive.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil {
                        val currentText = httpGet(port, "/text.txt")
                        currentText.contains(title) &&
                            currentText.contains(readinessText) &&
                            currentText.contains("RENDER.") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    val html = httpGet(port, "/")
                    assertContains(text, title)

                    val images = pngDataUris(svg)
                    assertTrue(images.isNotEmpty(), "Expected an embedded framebuffer PNG in SVG")
                    val stats = images.map { imageStats(it.id, it.bytes) }

                    val logResult = container.execInContainer("sh", "-lc", "kill $(cat /tmp/awt-primitive.pid) 2>/dev/null || true; cat /tmp/awt-primitive.log")
                    server.close()
                    serverThread.join(1_000)
                    return ProbeResult(text, svg, html, stats, logResult.stdout + logResult.stderr)
                }
        }
    }

    private fun runRobotProbeAgainstXvfb(
        mainClass: String,
        source: String,
    ): VisualProbeCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileProbe(container, mainClass, source)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 java -cp /tmp -Djava.awt.headless=false -Dsun.java2d.xrender=True -Dsun.java2d.opengl=false $mainClass
                    status=${'$'}?
                    exit "${'$'}status"
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualProbeCapture(result.stdout)
            }
    }

    private fun runRobotProbeAgainstKotlinServer(
        port: Int,
        title: String,
        mainClass: String,
        source: String,
    ): KotlinVisualProbeResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileProbe(container, mainClass, source)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        DISPLAY=host.docker.internal:$display \
                        nohup java -cp /tmp \
                          -Djava.awt.headless=false \
                          -Dsun.java2d.xrender=True \
                          -Dsun.java2d.opengl=false \
                          -DvisualProbe.holdMillis=60000 \
                          $mainClass >/tmp/visual-parity.log 2>&1 &
                        echo ${'$'}! >/tmp/visual-parity.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/visual-parity.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "Visual parity probe did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val log = container.execInContainer("sh", "-lc", "cat /tmp/visual-parity.log 2>/dev/null || true")
                        val currentText = httpGet(port, "/text.txt")
                        log.stdout.contains("PNG_BASE64=") &&
                            currentText.contains(title) &&
                            currentText.contains("RENDER.") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    val log = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        pid=${'$'}(cat /tmp/visual-parity.pid)
                        if ! kill -0 "${'$'}pid" 2>/dev/null; then
                          echo "VisualParityProbe exited before SVG snapshot completed" >&2
                          cat /tmp/visual-parity.log 2>/dev/null || true
                          exit 1
                        fi
                        cat /tmp/visual-parity.log
                        kill "${'$'}pid" 2>/dev/null || true
                        """.trimIndent(),
                    )
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, log.exitCode, log.stderr + log.stdout)
                    return KotlinVisualProbeResult(
                        robot = visualProbeCapture(log.stdout),
                        text = text,
                        svg = svg,
                        exportedFramebuffers = pngDataUris(svg).mapNotNull { embeddedPng ->
                            ImageIO.read(ByteArrayInputStream(embeddedPng.bytes))?.let(::visualProbeCapture)
                        },
                    )
                }
        }
    }

    private fun compileProbe(container: GenericContainer<*>, mainClass: String, source: String) {
        val sourceResult = container.execInContainer("sh", "-lc", "cat > /tmp/$mainClass.java <<'JAVA'\n$source\nJAVA\njavac /tmp/$mainClass.java")
        assertEquals(0, sourceResult.exitCode, sourceResult.stderr + sourceResult.stdout)
    }

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                val tag = match.value
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                EmbeddedPng(id, Base64.getDecoder().decode(encoded))
            }
            .toList()

    private fun String.hasSvgClass(className: String): Boolean =
        Regex("""\bclass="([^"]*)"""")
            .findAll(this)
            .any { match -> match.groupValues[1].split(' ').any { it == className } }

    private fun imageStats(id: String, bytes: ByteArray): ImageStats {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return ImageStats(id, 0, 0, 0, emptyList())
        var count = 0
        val samples = linkedSetOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                if (samples.size < 8) samples += argb
                val alpha = (argb ushr 24) and 0xff
                val rgb = argb and 0x00ff_ffff
                if (alpha > 0 && rgb != 0x00ff_ffff) count++
            }
        }
        return ImageStats(id, image.width, image.height, count, samples.map { "0x${it.toUInt().toString(16)}" })
    }

    private fun visualProbeCapture(stdout: String): VisualProbeCapture {
        val encoded = stdout.lineSequence()
            .firstOrNull { it.startsWith("PNG_BASE64=") }
            ?.removePrefix("PNG_BASE64=")
            ?: error("VisualParityProbe did not print PNG_BASE64, stdout:\n$stdout")
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            ?: error("VisualParityProbe PNG was not readable")
        return visualProbeCapture(image)
    }

    private fun visualProbeCapture(image: java.awt.image.BufferedImage): VisualProbeCapture {
        var nonBackground = 0
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                redSum += (argb ushr 16) and 0xff
                greenSum += (argb ushr 8) and 0xff
                blueSum += argb and 0xff
                if (rgbDistance(argb, VisualProbeBackground) > 8) nonBackground++
            }
        }
        val pixels = image.width * image.height
        val samples = VisualProbeSamplePoints
            .filter { point -> point.first in 0 until image.width && point.second in 0 until image.height }
            .associateWith { point -> image.getRGB(point.first, point.second) }
        return VisualProbeCapture(
            width = image.width,
            height = image.height,
            nonBackgroundPixels = nonBackground,
            averageRgb = (redSum + greenSum + blueSum).toDouble() / (pixels * 3.0 * 255.0),
            sampleArgb = samples,
            image = image,
        )
    }

    private fun assertClose(expected: Int, actual: Int, tolerance: Double, message: String) {
        val allowed = (expected * tolerance).toInt().coerceAtLeast(1)
        assertTrue(abs(expected - actual) <= allowed, message)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, message)
    }

    private fun imageDistance(reference: java.awt.image.BufferedImage, actual: java.awt.image.BufferedImage): Double {
        var total = 0L
        var samples = 0
        for (y in 0 until reference.height step 3) {
            for (x in 0 until reference.width step 3) {
                total += rgbDistance(reference.getRGB(x, y), actual.getRGB(x, y))
                samples++
            }
        }
        return total.toDouble() / samples.toDouble()
    }

    private fun rgbDistance(left: Int, right: Int): Int =
        abs(((left ushr 16) and 0xff) - ((right ushr 16) and 0xff)) +
            abs(((left ushr 8) and 0xff) - ((right ushr 8) and 0xff)) +
            abs((left and 0xff) - (right and 0xff))

    private fun Int.hexArgb(): String = "0x${toUInt().toString(16).padStart(8, '0')}"

    private data class EmbeddedPng(
        val id: String,
        val bytes: ByteArray,
    )

    private data class ImageStats(
        val id: String,
        val width: Int,
        val height: Int,
        val nonWhitePixels: Int,
        val samples: List<String>,
    )

    private data class VisualProbeCapture(
        val width: Int,
        val height: Int,
        val nonBackgroundPixels: Int,
        val averageRgb: Double,
        val sampleArgb: Map<Pair<Int, Int>, Int>,
        val image: java.awt.image.BufferedImage,
    ) {
        override fun toString(): String =
            "VisualProbeCapture(width=$width, height=$height, nonBackgroundPixels=$nonBackgroundPixels, averageRgb=$averageRgb, sampleArgb=${
                sampleArgb.mapValues { "0x${it.value.toUInt().toString(16).padStart(8, '0')}" }
            })"
    }

    private data class KotlinVisualProbeResult(
        val robot: VisualProbeCapture,
        val text: String,
        val svg: String,
        val exportedFramebuffers: List<VisualProbeCapture>,
    )

    private data class ProbeResult(
        val text: String,
        val svg: String,
        val html: String,
        val stats: List<ImageStats>,
        val log: String,
    )

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun waitUntil(
        failureMessage: () -> String = { "Condition did not become true before timeout" },
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 20_000
        var failure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (t: Throwable) {
                failure = t
            }
            Thread.sleep(100)
        }
        failure?.let { throw it }
        assertTrue(condition(), failureMessage())
    }

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun assumeDockerAndImage(image: String) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val imageExists = runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess
        assumeTrue(imageExists, "Build $image first with ./gradlew dockerBuildX11Client")
    }

    private companion object {
        const val CLIENT_IMAGE = "jonnyzzz-x/x11-client:latest"
        const val REFERENCE_IMAGE = "jonnyzzz-x/x11-reference:latest"
        const val VisualProbeBackground = 0xff14_1e32.toInt()
        val VisualProbeSamplePoints = listOf(
            30 to 30,
            76 to 46,
            156 to 42,
            246 to 40,
            310 to 82,
            54 to 168,
            280 to 186,
        )

        val AwtPrimitiveProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.GradientPaint;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.RenderingHints;
            import java.awt.Shape;
            import java.awt.image.BufferedImage;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.SwingUtilities;

            public class AwtPrimitiveProbe {
              public static void main(String[] args) throws Exception {
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Primitive Probe");
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(80, 70, 420, 320);
                  frame.setContentPane(new ProbeComponent());
                  frame.setVisible(true);
                  frame.toFront();
                  frame.repaint();
                  ((JComponent) frame.getContentPane()).paintImmediately(0, 0, 420, 320);
                  new javax.swing.Timer(250, event -> frame.repaint()).start();
                });
                Thread.sleep(60_000);
              }

              static final class ProbeComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(12, 38, 86));
                    g.fillRect(0, 0, getWidth(), getHeight());

                    g.setColor(new Color(240, 60, 40));
                    g.fillRect(24, 24, 160, 80);

                    g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
                    g.setColor(new Color(30, 220, 100));
                    g.fillOval(90, 52, 150, 120);
                    g.setComposite(AlphaComposite.SrcOver);

                    g.setPaint(new GradientPaint(24, 205, new Color(255, 210, 40), 300, 260, new Color(40, 160, 255)));
                    g.fillRoundRect(24, 205, 300, 58, 18, 18);

                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.BOLD, 24));
                    g.drawString("AWT probe", 30, 180);

                    BufferedImage image = new BufferedImage(96, 64, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < image.getHeight(); y++) {
                      for (int x = 0; x < image.getWidth(); x++) {
                        int alpha = 64 + (x * 191 / Math.max(1, image.getWidth() - 1));
                        int red = 30 + y * 3;
                        int green = 80 + x;
                        int blue = 220;
                        image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                      }
                    }
                    g.drawImage(image, 260, 36, null);

                    Shape oldClip = g.getClip();
                    g.setClip(260, 130, 90, 40);
                    g.setColor(new Color(255, 255, 255, 160));
                    g.fillRect(220, 120, 180, 70);
                    g.setClip(oldClip);

                    g.setColor(new Color(20, 20, 24));
                    g.drawLine(24, 290, 390, 275);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val AwtBufferStrategyProbeSource =
            """
            import java.awt.Canvas;
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics2D;
            import java.awt.image.BufferStrategy;
            import javax.swing.JFrame;
            import javax.swing.SwingUtilities;

            public class AwtBufferStrategyProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final Canvas[] canvasHolder = new Canvas[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT BufferStrategy Probe");
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setIgnoreRepaint(true);
                  Canvas canvas = new Canvas();
                  canvas.setIgnoreRepaint(true);
                  frame.add(canvas);
                  frame.setBounds(90, 90, 430, 330);
                  frame.setVisible(true);
                  frame.toFront();
                  canvas.createBufferStrategy(2);
                  frameHolder[0] = frame;
                  canvasHolder[0] = canvas;
                });
                JFrame frame = frameHolder[0];
                Canvas canvas = canvasHolder[0];
                long deadline = System.currentTimeMillis() + 60_000;
                while (System.currentTimeMillis() < deadline) {
                  render(canvas.getBufferStrategy(), canvas.getWidth(), canvas.getHeight());
                  Thread.sleep(200);
                }
              }

              private static void render(BufferStrategy strategy, int width, int height) {
                do {
                  do {
                    Graphics2D g = (Graphics2D) strategy.getDrawGraphics();
                    try {
                      g.setColor(new Color(22, 32, 48));
                      g.fillRect(0, 0, width, height);
                      g.setColor(new Color(245, 142, 45));
                      g.fillRect(24, 48, 180, 90);
                      g.setColor(new Color(58, 196, 125));
                      g.fillOval(140, 82, 150, 120);
                      g.setColor(new Color(234, 240, 248));
                      g.setFont(new Font("SansSerif", Font.BOLD, 24));
                      g.drawString("BufferStrategy", 32, 212);
                      g.setColor(new Color(88, 166, 255));
                      g.fillRect(32, 244, 340, 28);
                    } finally {
                      g.dispose();
                    }
                  } while (strategy.contentsRestored());
                  strategy.show();
                } while (strategy.contentsLost());
              }
            }
            """.trimIndent()

        val AwtTextProbeSource =
            """
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.RenderingHints;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.SwingUtilities;

            public class AwtTextProbe {
              public static void main(String[] args) throws Exception {
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Text Probe");
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(100, 100, 430, 240);
                  frame.setContentPane(new TextComponent());
                  frame.setVisible(true);
                  frame.toFront();
                  ((JComponent) frame.getContentPane()).paintImmediately(0, 0, 430, 240);
                  new javax.swing.Timer(250, event -> frame.repaint()).start();
                });
                Thread.sleep(60_000);
              }

              static final class TextComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(16, 24, 40));
                    g.setFont(new Font("SansSerif", Font.BOLD, 34));
                    g.drawString("Text glyph probe", 24, 88);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 24));
                    g.drawString("AI eyes need readable UI text", 24, 138);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualParityProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.GradientPaint;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.SwingUtilities;

            public class VisualParityProbe {
              public static void main(String[] args) throws Exception {
                final ProbeComponent component = new ProbeComponent();
                final JFrame[] frameHolder = new JFrame[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Visual Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  frame.setContentPane(component);
                  frame.setVisible(true);
                  frame.toFront();
                  component.paintImmediately(0, 0, 360, 240);
                  frameHolder[0] = frame;
                });
                Thread.sleep(800);
                Point location = component.getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(location.x, location.y, component.getWidth(), component.getHeight()));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
              }

              static final class ProbeComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());

                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(20, 20, 92, 54);

                    g.setComposite(AlphaComposite.SrcOver.derive(0.50f));
                    g.setColor(new Color(40, 220, 90));
                    g.fillRect(58, 34, 88, 62);
                    g.setComposite(AlphaComposite.SrcOver);

                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(150, 22, 76, 52);

                    BufferedImage stamp = new BufferedImage(58, 58, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < stamp.getHeight(); y++) {
                      for (int x = 0; x < stamp.getWidth(); x++) {
                        int alpha = 96 + x * 128 / Math.max(1, stamp.getWidth() - 1);
                        int red = 248;
                        int green = 216 - y;
                        int blue = 36 + x / 2;
                        stamp.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                      }
                    }
                    g.drawImage(stamp, 242, 28, null);

                    g.setPaint(new GradientPaint(28, 158, new Color(255, 225, 64), 330, 202, new Color(78, 190, 255)));
                    g.fillRect(28, 158, 304, 34);

                    g.setColor(new Color(255, 255, 255));
                    g.drawLine(20, 216, 338, 204);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualOverlapProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.SwingUtilities;

            public class VisualOverlapProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frames = new JFrame[2];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame lower = new JFrame("AWT Overlap Parity Probe lower");
                  lower.setUndecorated(true);
                  lower.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  lower.setBounds(40, 40, 360, 240);
                  lower.setContentPane(new LayerComponent(false));
                  lower.setVisible(true);
                  ((JComponent) lower.getContentPane()).paintImmediately(0, 0, 360, 240);

                  JFrame upper = new JFrame("AWT Overlap Parity Probe upper");
                  upper.setUndecorated(true);
                  upper.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  upper.setBounds(150, 95, 230, 165);
                  upper.setContentPane(new LayerComponent(true));
                  upper.setVisible(true);
                  upper.toFront();
                  ((JComponent) upper.getContentPane()).paintImmediately(0, 0, 230, 165);

                  frames[0] = lower;
                  frames[1] = upper;
                });
                Thread.sleep(1000);
                Point origin = frames[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  frames[1].dispose();
                  frames[0].dispose();
                });
              }

              static final class LayerComponent extends JComponent {
                private final boolean upper;

                LayerComponent(boolean upper) {
                  this.upper = upper;
                }

                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (upper) {
                      g.setColor(new Color(30, 42, 70));
                      g.fillRect(0, 0, getWidth(), getHeight());
                      g.setComposite(AlphaComposite.SrcOver.derive(0.82f));
                      g.setColor(new Color(64, 196, 125));
                      g.fillOval(18, 18, 142, 108);
                      g.setComposite(AlphaComposite.SrcOver);
                      g.setColor(new Color(255, 216, 64));
                      g.fillRect(106, 42, 90, 76);
                      g.setColor(Color.WHITE);
                      g.setFont(new Font("SansSerif", Font.BOLD, 22));
                      g.drawString("upper", 28, 146);
                    } else {
                      g.setColor(new Color(20, 30, 50));
                      g.fillRect(0, 0, getWidth(), getHeight());
                      g.setColor(new Color(230, 50, 44));
                      g.fillRect(20, 20, 110, 70);
                      g.setComposite(AlphaComposite.SrcOver.derive(0.58f));
                      g.setColor(new Color(42, 168, 255));
                      g.fillRect(78, 54, 132, 92);
                      g.setComposite(AlphaComposite.SrcOver);
                      g.setColor(new Color(238, 244, 250));
                      g.setFont(new Font("SansSerif", Font.BOLD, 22));
                      g.drawString("lower", 26, 142);
                    }
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualPopupProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JWindow;
            import javax.swing.SwingUtilities;

            public class VisualPopupProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final JWindow[] popupHolder = new JWindow[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Popup Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  frame.setContentPane(new OwnerComponent());
                  frame.setVisible(true);
                  ((JComponent) frame.getContentPane()).paintImmediately(0, 0, 360, 240);

                  JWindow popup = new JWindow(frame);
                  popup.setBounds(132, 86, 210, 150);
                  popup.setContentPane(new PopupComponent());
                  popup.setVisible(true);
                  popup.toFront();
                  ((JComponent) popup.getContentPane()).paintImmediately(0, 0, 210, 150);

                  frameHolder[0] = frame;
                  popupHolder[0] = popup;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  popupHolder[0].dispose();
                  frameHolder[0].dispose();
                });
              }

              static final class OwnerComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 20, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.56f));
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(74, 50, 138, 96);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(24, 172, 312, 34);
                    g.setColor(new Color(238, 244, 250));
                    g.setFont(new Font("SansSerif", Font.BOLD, 22));
                    g.drawString("owner", 26, 136);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class PopupComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(245, 248, 252));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(36, 52, 76));
                    g.fillRect(0, 0, getWidth(), 30);
                    g.setColor(new Color(64, 196, 125));
                    g.fillRect(18, 48, 78, 58);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.72f));
                    g.setColor(new Color(245, 142, 45));
                    g.fillOval(72, 62, 94, 62);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(20, 30, 50));
                    g.setFont(new Font("SansSerif", Font.BOLD, 20));
                    g.drawString("popup", 22, 138);
                    g.setColor(Color.WHITE);
                    g.drawString("menu", 16, 22);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualDialogProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JComponent;
            import javax.swing.JDialog;
            import javax.swing.JFrame;
            import javax.swing.SwingUtilities;

            public class VisualDialogProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final JDialog[] dialogHolder = new JDialog[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Dialog Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  frame.setContentPane(new OwnerComponent());
                  frame.setVisible(true);
                  ((JComponent) frame.getContentPane()).paintImmediately(0, 0, 360, 240);

                  JDialog dialog = new JDialog(frame, "AWT Dialog Parity Probe dialog", false);
                  dialog.setUndecorated(true);
                  dialog.setBounds(120, 86, 240, 170);
                  dialog.setContentPane(new DialogComponent());
                  dialog.setVisible(true);
                  dialog.toFront();
                  ((JComponent) dialog.getContentPane()).paintImmediately(0, 0, 240, 170);

                  frameHolder[0] = frame;
                  dialogHolder[0] = dialog;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  dialogHolder[0].dispose();
                  frameHolder[0].dispose();
                });
              }

              static final class OwnerComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 20, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.52f));
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(74, 50, 138, 96);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(24, 172, 312, 34);
                    g.setColor(new Color(238, 244, 250));
                    g.setFont(new Font("SansSerif", Font.BOLD, 22));
                    g.drawString("owner", 26, 136);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class DialogComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(245, 248, 252));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(36, 52, 76));
                    g.fillRect(0, 0, getWidth(), 34);
                    g.setColor(new Color(64, 196, 125));
                    g.fillRect(18, 54, 86, 66);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.76f));
                    g.setColor(new Color(245, 142, 45));
                    g.fillOval(86, 64, 106, 74);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(20, 30, 50));
                    g.setFont(new Font("SansSerif", Font.BOLD, 20));
                    g.drawString("dialog", 22, 152);
                    g.setColor(Color.WHITE);
                    g.drawString("owned", 16, 24);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualPopupMenuProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Dimension;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.BorderFactory;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JPopupMenu;
            import javax.swing.SwingUtilities;

            public class VisualPopupMenuProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final JPopupMenu[] menuHolder = new JPopupMenu[1];
                SwingUtilities.invokeAndWait(() -> {
                  JPopupMenu.setDefaultLightWeightPopupEnabled(false);

                  JFrame frame = new JFrame("AWT PopupMenu Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  frame.setContentPane(new OwnerComponent());
                  frame.setVisible(true);
                  ((JComponent) frame.getContentPane()).paintImmediately(0, 0, 360, 240);

                  JPopupMenu menu = new JPopupMenu();
                  menu.setLightWeightPopupEnabled(false);
                  menu.setBorder(BorderFactory.createEmptyBorder());
                  MenuComponent content = new MenuComponent();
                  content.setPreferredSize(new Dimension(220, 150));
                  menu.add(content);
                  menu.setPopupSize(220, 150);
                  menu.show(frame.getContentPane(), 80, 54);
                  menu.paintImmediately(0, 0, 220, 150);

                  frameHolder[0] = frame;
                  menuHolder[0] = menu;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  menuHolder[0].setVisible(false);
                  frameHolder[0].dispose();
                });
              }

              static final class OwnerComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 20, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.54f));
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(74, 50, 138, 96);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(24, 172, 312, 34);
                    g.setColor(new Color(238, 244, 250));
                    g.setFont(new Font("SansSerif", Font.BOLD, 22));
                    g.drawString("owner", 26, 136);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class MenuComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(245, 248, 252));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(36, 52, 76));
                    g.fillRect(0, 0, getWidth(), 30);
                    g.setColor(new Color(64, 196, 125));
                    g.fillRect(18, 46, 74, 56);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.70f));
                    g.setColor(new Color(245, 142, 45));
                    g.fillOval(70, 58, 92, 60);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(20, 30, 50));
                    g.setFont(new Font("SansSerif", Font.BOLD, 20));
                    g.drawString("popup menu", 22, 136);
                    g.setColor(Color.WHITE);
                    g.drawString("actions", 16, 22);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualMenuDropdownProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Dimension;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.BorderFactory;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JMenu;
            import javax.swing.JMenuBar;
            import javax.swing.JPopupMenu;
            import javax.swing.SwingUtilities;

            public class VisualMenuDropdownProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final JPopupMenu[] popupHolder = new JPopupMenu[1];
                SwingUtilities.invokeAndWait(() -> {
                  JPopupMenu.setDefaultLightWeightPopupEnabled(false);

                  JFrame frame = new JFrame("AWT Menu Dropdown Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  JMenuBar menuBar = new JMenuBar();
                  menuBar.setBorder(BorderFactory.createEmptyBorder());
                  menuBar.setOpaque(true);
                  menuBar.setBackground(new Color(36, 52, 76));
                  JMenu menu = new JMenu("Actions");
                  menu.setForeground(Color.WHITE);
                  menu.setOpaque(true);
                  menu.setBackground(new Color(36, 52, 76));
                  menuBar.add(menu);
                  frame.setJMenuBar(menuBar);
                  OwnerComponent owner = new OwnerComponent();
                  frame.setContentPane(owner);
                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);

                  JPopupMenu popup = menu.getPopupMenu();
                  popup.setLightWeightPopupEnabled(false);
                  popup.setBorder(BorderFactory.createEmptyBorder());
                  DropdownComponent dropdown = new DropdownComponent();
                  dropdown.setPreferredSize(new Dimension(230, 140));
                  popup.add(dropdown);
                  popup.setPopupSize(230, 140);
                  popup.show(menu, 0, menu.getHeight());
                  popup.paintImmediately(0, 0, 230, 140);

                  frameHolder[0] = frame;
                  popupHolder[0] = popup;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  popupHolder[0].setVisible(false);
                  frameHolder[0].dispose();
                });
              }

              static final class OwnerComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 20, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.56f));
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(74, 50, 138, 96);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(24, 172, 312, 34);
                    g.setColor(new Color(238, 244, 250));
                    g.setFont(new Font("SansSerif", Font.BOLD, 22));
                    g.drawString("owner", 26, 136);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class DropdownComponent extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(245, 248, 252));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(36, 52, 76));
                    g.fillRect(0, 0, getWidth(), 32);
                    g.setColor(new Color(64, 196, 125));
                    g.fillRect(18, 48, 86, 58);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.72f));
                    g.setColor(new Color(245, 142, 45));
                    g.fillOval(84, 58, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(20, 30, 50));
                    g.setFont(new Font("SansSerif", Font.BOLD, 20));
                    g.drawString("dropdown", 22, 126);
                    g.setColor(Color.WHITE);
                    g.drawString("actions", 16, 23);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualComboDropdownProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Component;
            import java.awt.Dimension;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.DefaultListCellRenderer;
            import javax.swing.JComboBox;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JList;
            import javax.swing.JPanel;
            import javax.swing.JPopupMenu;
            import javax.swing.SwingUtilities;

            public class VisualComboDropdownProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final JComboBox<String>[] comboHolder = new JComboBox[1];
                SwingUtilities.invokeAndWait(() -> {
                  JPopupMenu.setDefaultLightWeightPopupEnabled(false);

                  JFrame frame = new JFrame("AWT Combo Dropdown Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  OwnerPanel owner = new OwnerPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  JComboBox<String> combo = new JComboBox<>(new String[] {
                    "Project SDK",
                    "Run Configuration",
                    "Module Source",
                    "Inspection Profile"
                  });
                  combo.setLightWeightPopupEnabled(false);
                  combo.setMaximumRowCount(4);
                  combo.setSelectedIndex(0);
                  combo.setRenderer(new StripeRenderer());
                  combo.setBounds(78, 58, 204, 34);
                  owner.add(combo);

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  combo.showPopup();
                  combo.paintImmediately(0, 0, 204, 34);

                  frameHolder[0] = frame;
                  comboHolder[0] = combo;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  comboHolder[0].hidePopup();
                  frameHolder[0].dispose();
                });
              }

              static final class OwnerPanel extends JPanel {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 20, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.54f));
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(74, 50, 138, 96);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(24, 172, 312, 34);
                    g.setColor(new Color(238, 244, 250));
                    g.setFont(new Font("SansSerif", Font.BOLD, 22));
                    g.drawString("combo owner", 26, 136);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class StripeRenderer extends DefaultListCellRenderer {
                @Override
                public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
                ) {
                  JComponent component = (JComponent) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                  component.setPreferredSize(new Dimension(204, 28));
                  component.setFont(new Font("SansSerif", Font.BOLD, 14));
                  component.setForeground(index == 0 ? Color.WHITE : new Color(20, 30, 50));
                  component.setBackground(index == 0 ? new Color(36, 52, 76) : new Color(245, 248, 252));
                  component.setOpaque(true);
                  return component;
                }
              }
            }
            """.trimIndent()

        val VisualTooltipProbeSource =
            """
            import java.awt.AlphaComposite;
            import java.awt.Color;
            import java.awt.Dimension;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.event.MouseEvent;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JToolTip;
            import javax.swing.SwingUtilities;
            import javax.swing.ToolTipManager;

            public class VisualTooltipProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final TargetComponent[] targetHolder = new TargetComponent[1];
                SwingUtilities.invokeAndWait(() -> {
                  ToolTipManager manager = ToolTipManager.sharedInstance();
                  manager.setEnabled(true);
                  manager.setLightWeightPopupEnabled(false);
                  manager.setInitialDelay(0);
                  manager.setReshowDelay(0);
                  manager.setDismissDelay(60000);

                  JFrame frame = new JFrame("AWT Tooltip Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  OwnerPanel owner = new OwnerPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  TargetComponent target = new TargetComponent();
                  target.setBounds(42, 44, 154, 74);
                  target.setToolTipText("Semantic tooltip");
                  owner.add(target);
                  manager.registerComponent(target);

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  target.paintImmediately(0, 0, 154, 74);

                  long now = System.currentTimeMillis();
                  MouseEvent entered = new MouseEvent(target, MouseEvent.MOUSE_ENTERED, now, 0, 28, 28, 1, false);
                  MouseEvent moved = new MouseEvent(target, MouseEvent.MOUSE_MOVED, now + 1, 0, 28, 28, 1, false);
                  manager.mouseEntered(entered);
                  manager.mouseMoved(moved);

                  frameHolder[0] = frame;
                  targetHolder[0] = target;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> {
                  ToolTipManager.sharedInstance().unregisterComponent(targetHolder[0]);
                  frameHolder[0].dispose();
                });
              }

              static final class OwnerPanel extends JComponent {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 20, 104, 62);
                    g.setComposite(AlphaComposite.SrcOver.derive(0.54f));
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(74, 50, 138, 96);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(24, 172, 312, 34);
                    g.setColor(new Color(238, 244, 250));
                    g.setFont(new Font("SansSerif", Font.BOLD, 22));
                    g.drawString("tooltip owner", 26, 136);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class TargetComponent extends JComponent {
                @Override
                public Point getToolTipLocation(MouseEvent event) {
                  return new Point(72, 52);
                }

                @Override
                public JToolTip createToolTip() {
                  JToolTip tip = new PaintedToolTip();
                  tip.setComponent(this);
                  return tip;
                }

                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(245, 248, 252));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(36, 52, 76));
                    g.fillRect(0, 0, getWidth(), 26);
                    g.setColor(new Color(64, 196, 125));
                    g.fillRect(14, 40, 56, 22);
                    g.setColor(new Color(20, 30, 50));
                    g.setFont(new Font("SansSerif", Font.BOLD, 15));
                    g.drawString("hover target", 14, 24);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class PaintedToolTip extends JToolTip {
                PaintedToolTip() {
                  setPreferredSize(new Dimension(190, 82));
                  setOpaque(true);
                }

                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(245, 248, 252));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(36, 52, 76));
                    g.fillRect(0, 0, getWidth(), 28);
                    g.setColor(new Color(245, 142, 45));
                    g.fillOval(124, 42, 46, 28);
                    g.setColor(new Color(64, 196, 125));
                    g.fillRect(18, 44, 72, 20);
                    g.setColor(new Color(20, 30, 50));
                    g.setFont(new Font("SansSerif", Font.BOLD, 17));
                    g.drawString("tooltip", 18, 66);
                    g.setColor(Color.WHITE);
                    g.drawString("details", 14, 21);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualDenseSwingProbeSource =
            """
            import java.awt.Color;
            import java.awt.Component;
            import java.awt.Dimension;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JLabel;
            import javax.swing.JPanel;
            import javax.swing.JScrollPane;
            import javax.swing.JTable;
            import javax.swing.JTree;
            import javax.swing.SwingConstants;
            import javax.swing.SwingUtilities;
            import javax.swing.border.EmptyBorder;
            import javax.swing.table.DefaultTableCellRenderer;
            import javax.swing.table.DefaultTableModel;
            import javax.swing.tree.DefaultMutableTreeNode;
            import javax.swing.tree.DefaultTreeCellRenderer;

            public class VisualDenseSwingProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Dense Swing Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  DashboardPanel owner = new DashboardPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  JTable table = new JTable(new DefaultTableModel(
                      new Object[][] {
                        {"core", "91%", "ready"},
                        {"render", "84%", "watch"},
                        {"input", "77%", "pass"},
                        {"xkb", "95%", "ready"},
                        {"awt", "88%", "probe"}
                      },
                      new Object[] {"Area", "Score", "State"}
                  ));
                  table.setName("DenseSwingTable");
                  table.setRowHeight(22);
                  table.setShowGrid(false);
                  table.setIntercellSpacing(new Dimension(0, 0));
                  table.setTableHeader(null);
                  table.setFont(new Font("SansSerif", Font.BOLD, 13));
                  table.setDefaultRenderer(Object.class, new TableRenderer());
                  JScrollPane tableScroll = new JScrollPane(table);
                  tableScroll.setBounds(18, 54, 186, 124);
                  tableScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                  owner.add(tableScroll);

                  DefaultMutableTreeNode root = new DefaultMutableTreeNode("project");
                  DefaultMutableTreeNode src = new DefaultMutableTreeNode("src");
                  src.add(new DefaultMutableTreeNode("main"));
                  src.add(new DefaultMutableTreeNode("test"));
                  DefaultMutableTreeNode workflow = new DefaultMutableTreeNode("workflow");
                  workflow.add(new DefaultMutableTreeNode("matrix"));
                  workflow.add(new DefaultMutableTreeNode("agents"));
                  root.add(src);
                  root.add(workflow);
                  JTree tree = new JTree(root);
                  tree.setName("DenseSwingTree");
                  tree.setRowHeight(22);
                  tree.setShowsRootHandles(true);
                  tree.setRootVisible(true);
                  tree.setFont(new Font("SansSerif", Font.PLAIN, 13));
                  tree.setCellRenderer(new TreeRenderer());
                  for (int row = 0; row < tree.getRowCount(); row++) {
                    tree.expandRow(row);
                  }
                  JScrollPane treeScroll = new JScrollPane(tree);
                  treeScroll.setBounds(214, 54, 128, 124);
                  treeScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                  owner.add(treeScroll);

                  JLabel footer = new JLabel("retained Swing surfaces", SwingConstants.CENTER);
                  footer.setOpaque(true);
                  footer.setForeground(new Color(20, 30, 50));
                  footer.setBackground(new Color(255, 225, 64));
                  footer.setFont(new Font("SansSerif", Font.BOLD, 15));
                  footer.setBounds(18, 190, 324, 28);
                  owner.add(footer);

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  table.paintImmediately(0, 0, table.getWidth(), table.getHeight());
                  tree.paintImmediately(0, 0, tree.getWidth(), tree.getHeight());
                  frameHolder[0] = frame;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
              }

              static final class DashboardPanel extends JPanel {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 18, 128, 24);
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(154, 18, 188, 24);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.BOLD, 18));
                    g.drawString("dense Swing", 28, 37);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class TableRenderer extends DefaultTableCellRenderer {
                @Override
                public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column
                ) {
                  JComponent component = (JComponent) super.getTableCellRendererComponent(table, value, false, false, row, column);
                  component.setOpaque(true);
                  component.setForeground(column == 2 ? Color.WHITE : new Color(20, 30, 50));
                  component.setBackground(column == 2 ? new Color(36, 52, 76) : (row % 2 == 0 ? new Color(245, 248, 252) : new Color(225, 234, 242)));
                  component.setBorder(new EmptyBorder(0, 8, 0, 8));
                  return component;
                }
              }

              static final class TreeRenderer extends DefaultTreeCellRenderer {
                @Override
                public Component getTreeCellRendererComponent(
                    JTree tree,
                    Object value,
                    boolean selected,
                    boolean expanded,
                    boolean leaf,
                    int row,
                    boolean hasFocus
                ) {
                  JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, value, false, expanded, leaf, row, false);
                  label.setOpaque(true);
                  label.setForeground(leaf ? new Color(20, 30, 50) : Color.WHITE);
                  label.setBackground(leaf ? new Color(245, 248, 252) : new Color(64, 196, 125));
                  label.setBorder(new EmptyBorder(0, 4, 0, 4));
                  return label;
                }
              }
            }
            """.trimIndent()

        val VisualFormControlsProbeSource =
            """
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JButton;
            import javax.swing.JCheckBox;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JLabel;
            import javax.swing.JPanel;
            import javax.swing.JProgressBar;
            import javax.swing.JRadioButton;
            import javax.swing.JScrollPane;
            import javax.swing.JSlider;
            import javax.swing.JTextArea;
            import javax.swing.JTextField;
            import javax.swing.SwingUtilities;
            import javax.swing.border.EmptyBorder;

            public class VisualFormControlsProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Form Controls Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  FormPanel owner = new FormPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  JLabel title = new JLabel("settings");
                  title.setForeground(Color.WHITE);
                  title.setFont(new Font("SansSerif", Font.BOLD, 18));
                  title.setBounds(24, 16, 120, 28);
                  owner.add(title);

                  JTextField field = new JTextField("Run configuration");
                  field.setFont(new Font("SansSerif", Font.BOLD, 14));
                  field.setBounds(24, 52, 192, 30);
                  field.setCaretPosition(0);
                  field.setEditable(false);
                  owner.add(field);

                  JCheckBox check = new JCheckBox("auto build", true);
                  check.setFont(new Font("SansSerif", Font.BOLD, 13));
                  check.setForeground(new Color(20, 30, 50));
                  check.setBackground(new Color(245, 248, 252));
                  check.setBounds(232, 50, 108, 30);
                  owner.add(check);

                  JRadioButton radio = new JRadioButton("local", true);
                  radio.setFont(new Font("SansSerif", Font.BOLD, 13));
                  radio.setForeground(new Color(20, 30, 50));
                  radio.setBackground(new Color(245, 248, 252));
                  radio.setBounds(232, 84, 108, 30);
                  owner.add(radio);

                  JSlider slider = new JSlider(0, 100, 72);
                  slider.setPaintTicks(true);
                  slider.setMajorTickSpacing(25);
                  slider.setOpaque(true);
                  slider.setBackground(new Color(245, 248, 252));
                  slider.setBounds(24, 96, 192, 42);
                  owner.add(slider);

                  JProgressBar progress = new JProgressBar(0, 100);
                  progress.setValue(64);
                  progress.setStringPainted(true);
                  progress.setString("64%");
                  progress.setFont(new Font("SansSerif", Font.BOLD, 12));
                  progress.setBounds(24, 148, 192, 26);
                  owner.add(progress);

                  JButton button = new JButton("Apply");
                  button.setFont(new Font("SansSerif", Font.BOLD, 14));
                  button.setBounds(232, 132, 108, 42);
                  owner.add(button);

                  JTextArea notes = new JTextArea("profile: default\nscope: project");
                  notes.setFont(new Font("SansSerif", Font.PLAIN, 13));
                  notes.setEditable(false);
                  notes.setBorder(new EmptyBorder(4, 6, 4, 6));
                  JScrollPane notesScroll = new JScrollPane(notes);
                  notesScroll.setBounds(24, 186, 316, 36);
                  notesScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                  owner.add(notesScroll);

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  frameHolder[0] = frame;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
              }

              static final class FormPanel extends JPanel {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 16, 128, 28);
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(154, 16, 188, 28);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(232, 184, 108, 38);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualTabbedSplitPaneProbeSource =
            """
            import java.awt.Color;
            import java.awt.Component;
            import java.awt.Dimension;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.DefaultListCellRenderer;
            import javax.swing.DefaultListModel;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JLabel;
            import javax.swing.JList;
            import javax.swing.JPanel;
            import javax.swing.JScrollPane;
            import javax.swing.JSplitPane;
            import javax.swing.JTabbedPane;
            import javax.swing.JTextPane;
            import javax.swing.ListSelectionModel;
            import javax.swing.SwingUtilities;
            import javax.swing.border.EmptyBorder;
            import javax.swing.text.SimpleAttributeSet;
            import javax.swing.text.StyleConstants;
            import javax.swing.text.StyledDocument;

            public class VisualTabbedSplitPaneProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Tabbed Split Pane Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  TabbedPanel owner = new TabbedPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  JLabel title = new JLabel("workspace");
                  title.setForeground(Color.WHITE);
                  title.setFont(new Font("SansSerif", Font.BOLD, 18));
                  title.setBounds(24, 16, 140, 26);
                  owner.add(title);

                  JTabbedPane tabs = new JTabbedPane();
                  tabs.setFont(new Font("SansSerif", Font.BOLD, 13));
                  tabs.setBounds(18, 52, 324, 166);
                  tabs.addTab("Project", splitPane());
                  tabs.addTab("Debug", debugPanel());
                  tabs.setSelectedIndex(0);
                  owner.add(tabs);

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  tabs.paintImmediately(0, 0, tabs.getWidth(), tabs.getHeight());
                  frameHolder[0] = frame;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
              }

              static JSplitPane splitPane() {
                DefaultListModel<String> model = new DefaultListModel<>();
                model.addElement("src/main");
                model.addElement("src/test");
                model.addElement("workflow");
                model.addElement("README.md");
                JList<String> list = new JList<>(model);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setSelectedIndex(1);
                list.setFixedCellHeight(24);
                list.setFont(new Font("SansSerif", Font.BOLD, 13));
                list.setCellRenderer(new ProjectListRenderer());
                JScrollPane listScroll = new JScrollPane(list);
                listScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                listScroll.setPreferredSize(new Dimension(118, 118));

                JTextPane editor = new JTextPane();
                editor.setEditable(false);
                editor.setFont(new Font("Monospaced", Font.BOLD, 13));
                editor.setBackground(new Color(245, 248, 252));
                editor.setBorder(new EmptyBorder(8, 10, 8, 10));
                StyledDocument doc = editor.getStyledDocument();
                try {
                  SimpleAttributeSet keyword = new SimpleAttributeSet();
                  StyleConstants.setForeground(keyword, new Color(230, 50, 44));
                  StyleConstants.setBold(keyword, true);
                  SimpleAttributeSet plain = new SimpleAttributeSet();
                  StyleConstants.setForeground(plain, new Color(20, 30, 50));
                  SimpleAttributeSet accent = new SimpleAttributeSet();
                  StyleConstants.setForeground(accent, new Color(42, 120, 200));
                  StyleConstants.setBold(accent, true);
                  doc.insertString(doc.getLength(), "fun ", keyword);
                  doc.insertString(doc.getLength(), "renderTabs() {\n", plain);
                  doc.insertString(doc.getLength(), "  split = ", plain);
                  doc.insertString(doc.getLength(), "stable\n", accent);
                  doc.insertString(doc.getLength(), "  tabs = 2\n}", plain);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
                JScrollPane editorScroll = new JScrollPane(editor);
                editorScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                editorScroll.setPreferredSize(new Dimension(184, 118));

                JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, editorScroll);
                split.setDividerLocation(118);
                split.setDividerSize(8);
                split.setResizeWeight(0.36);
                split.setContinuousLayout(true);
                split.setBorder(new EmptyBorder(0, 0, 0, 0));
                return split;
              }

              static JPanel debugPanel() {
                JPanel panel = new JPanel(null);
                panel.setBackground(new Color(245, 248, 252));
                return panel;
              }

              static final class TabbedPanel extends JPanel {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 16, 128, 28);
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(154, 16, 188, 28);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(18, 220, 324, 6);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class ProjectListRenderer extends DefaultListCellRenderer {
                @Override
                public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean cellHasFocus
                ) {
                  JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, false);
                  label.setOpaque(true);
                  label.setFont(new Font("SansSerif", Font.BOLD, 13));
                  label.setForeground(selected ? Color.WHITE : new Color(20, 30, 50));
                  label.setBackground(selected ? new Color(36, 52, 76) : (index % 2 == 0 ? new Color(245, 248, 252) : new Color(225, 234, 242)));
                  label.setBorder(new EmptyBorder(0, 8, 0, 8));
                  return label;
                }
              }
            }
            """.trimIndent()

        val VisualDesktopPaneProbeSource =
            """
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JButton;
            import javax.swing.JDesktopPane;
            import javax.swing.JFrame;
            import javax.swing.JInternalFrame;
            import javax.swing.JLabel;
            import javax.swing.JList;
            import javax.swing.JPanel;
            import javax.swing.JScrollPane;
            import javax.swing.JTextArea;
            import javax.swing.ListSelectionModel;
            import javax.swing.SwingUtilities;
            import javax.swing.border.EmptyBorder;

            public class VisualDesktopPaneProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Desktop Pane Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  DesktopOwnerPanel owner = new DesktopOwnerPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  JLabel title = new JLabel("desktop");
                  title.setForeground(Color.WHITE);
                  title.setFont(new Font("SansSerif", Font.BOLD, 18));
                  title.setBounds(24, 16, 120, 26);
                  owner.add(title);

                  JDesktopPane desktop = new JDesktopPane();
                  desktop.setBackground(new Color(225, 234, 242));
                  desktop.setBounds(18, 52, 324, 166);
                  owner.add(desktop);

                  JInternalFrame project = internalFrame("Project", 10, 10, 136, 132);
                  JList<String> files = new JList<>(new String[] {"src", "test", "workflow", "README.md"});
                  files.setFont(new Font("SansSerif", Font.BOLD, 13));
                  files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                  files.setSelectedIndex(2);
                  files.setFixedCellHeight(22);
                  JScrollPane filesScroll = new JScrollPane(files);
                  filesScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                  project.setContentPane(filesScroll);
                  desktop.add(project);
                  project.setVisible(true);

                  JInternalFrame editor = internalFrame("Editor", 104, 34, 198, 116);
                  JPanel editorPanel = new JPanel(null);
                  editorPanel.setBackground(new Color(245, 248, 252));
                  JTextArea text = new JTextArea("class Desktop {\n  frame = active\n  layer = tools\n}");
                  text.setEditable(false);
                  text.setFont(new Font("Monospaced", Font.BOLD, 13));
                  text.setForeground(new Color(20, 30, 50));
                  text.setBackground(new Color(245, 248, 252));
                  text.setBorder(new EmptyBorder(6, 8, 6, 8));
                  text.setBounds(0, 0, 188, 66);
                  editorPanel.add(text);
                  JButton run = new JButton("Run");
                  run.setFont(new Font("SansSerif", Font.BOLD, 13));
                  run.setBounds(112, 72, 72, 28);
                  editorPanel.add(run);
                  editor.setContentPane(editorPanel);
                  desktop.add(editor);
                  editor.setVisible(true);

                  try {
                    editor.setSelected(true);
                  } catch (Exception ignored) {
                  }

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  desktop.paintImmediately(0, 0, desktop.getWidth(), desktop.getHeight());
                  frameHolder[0] = frame;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
              }

              static JInternalFrame internalFrame(String title, int x, int y, int width, int height) {
                JInternalFrame frame = new JInternalFrame(title, false, false, false, false);
                frame.setBounds(x, y, width, height);
                frame.setFrameIcon(null);
                frame.setFont(new Font("SansSerif", Font.BOLD, 12));
                return frame;
              }

              static final class DesktopOwnerPanel extends JPanel {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 16, 128, 28);
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(154, 16, 188, 28);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(18, 220, 324, 6);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()

        val VisualLayeredOverlayProbeSource =
            """
            import java.awt.Color;
            import java.awt.Font;
            import java.awt.Graphics;
            import java.awt.Graphics2D;
            import java.awt.Point;
            import java.awt.Rectangle;
            import java.awt.RenderingHints;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;
            import javax.swing.JButton;
            import javax.swing.JComponent;
            import javax.swing.JFrame;
            import javax.swing.JLabel;
            import javax.swing.JLayeredPane;
            import javax.swing.JPanel;
            import javax.swing.JScrollPane;
            import javax.swing.JTextArea;
            import javax.swing.SwingConstants;
            import javax.swing.SwingUtilities;
            import javax.swing.border.EmptyBorder;

            public class VisualLayeredOverlayProbe {
              public static void main(String[] args) throws Exception {
                final JFrame[] frameHolder = new JFrame[1];
                final JComponent[] glassHolder = new JComponent[1];
                SwingUtilities.invokeAndWait(() -> {
                  JFrame frame = new JFrame("AWT Layered Overlay Parity Probe");
                  frame.setUndecorated(true);
                  frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                  frame.setBounds(40, 40, 360, 240);
                  LayeredOwnerPanel owner = new LayeredOwnerPanel();
                  owner.setLayout(null);
                  frame.setContentPane(owner);

                  JLabel title = new JLabel("overlays");
                  title.setForeground(Color.WHITE);
                  title.setFont(new Font("SansSerif", Font.BOLD, 18));
                  title.setBounds(24, 16, 126, 26);
                  owner.add(title);

                  JLayeredPane layered = new JLayeredPane();
                  layered.setOpaque(true);
                  layered.setBackground(new Color(225, 234, 242));
                  layered.setBounds(18, 52, 324, 146);
                  owner.add(layered);

                  JPanel editor = new JPanel(null);
                  editor.setBackground(new Color(245, 248, 252));
                  editor.setBounds(8, 8, 220, 122);
                  JTextArea text = new JTextArea("fun overlay() {\n  hint = visible\n  z = layered\n}");
                  text.setEditable(false);
                  text.setFont(new Font("Monospaced", Font.BOLD, 13));
                  text.setForeground(new Color(20, 30, 50));
                  text.setBackground(new Color(245, 248, 252));
                  text.setBorder(new EmptyBorder(8, 10, 8, 10));
                  JScrollPane textScroll = new JScrollPane(text);
                  textScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
                  textScroll.setBounds(0, 0, 220, 122);
                  editor.add(textScroll);
                  layered.add(editor, JLayeredPane.DEFAULT_LAYER);

                  JPanel palette = new JPanel(null);
                  palette.setBackground(new Color(36, 52, 76));
                  palette.setBounds(238, 10, 72, 118);
                  JLabel tool = new JLabel("tools", SwingConstants.CENTER);
                  tool.setFont(new Font("SansSerif", Font.BOLD, 12));
                  tool.setForeground(Color.WHITE);
                  tool.setBounds(8, 8, 56, 20);
                  palette.add(tool);
                  JButton run = new JButton("Run");
                  run.setFont(new Font("SansSerif", Font.BOLD, 12));
                  run.setBounds(8, 38, 56, 28);
                  palette.add(run);
                  JButton pin = new JButton("Pin");
                  pin.setFont(new Font("SansSerif", Font.BOLD, 12));
                  pin.setBounds(8, 76, 56, 28);
                  palette.add(pin);
                  layered.add(palette, JLayeredPane.PALETTE_LAYER);

                  JLabel notification = new JLabel(" indexing ready ");
                  notification.setOpaque(true);
                  notification.setForeground(new Color(20, 30, 50));
                  notification.setBackground(new Color(255, 225, 64));
                  notification.setFont(new Font("SansSerif", Font.BOLD, 13));
                  notification.setBounds(92, 88, 124, 30);
                  layered.add(notification, JLayeredPane.POPUP_LAYER);

                  OverlayGlassPane glass = new OverlayGlassPane();
                  frame.setGlassPane(glass);
                  glass.setVisible(true);
                  glassHolder[0] = glass;

                  frame.setVisible(true);
                  owner.paintImmediately(0, 0, 360, 240);
                  layered.paintImmediately(0, 0, layered.getWidth(), layered.getHeight());
                  glass.paintImmediately(0, 0, 360, 240);
                  frameHolder[0] = frame;
                });
                Thread.sleep(1000);
                Point origin = frameHolder[0].getLocationOnScreen();
                BufferedImage image = new Robot().createScreenCapture(new Rectangle(origin.x, origin.y, 360, 240));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
                System.out.flush();
                long holdMillis = Long.getLong("visualProbe.holdMillis", 0L);
                if (holdMillis > 0L) {
                  Thread.sleep(holdMillis);
                }
                SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());
              }

              static final class OverlayGlassPane extends JComponent {
                OverlayGlassPane() {
                  setOpaque(false);
                  setFont(new Font("SansSerif", Font.BOLD, 12));
                }

                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(230, 50, 44));
                    g.drawRect(34, 72, 184, 80);
                    g.drawRect(35, 73, 182, 78);
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(34, 164, 98, 24);
                    g.setColor(Color.WHITE);
                    g.drawString("glass hint", 46, 181);
                  } finally {
                    g.dispose();
                  }
                }
              }

              static final class LayeredOwnerPanel extends JPanel {
                @Override
                protected void paintComponent(Graphics graphics) {
                  Graphics2D g = (Graphics2D) graphics.create();
                  try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(20, 30, 50));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(230, 50, 44));
                    g.fillRect(18, 16, 128, 28);
                    g.setColor(new Color(42, 168, 255));
                    g.fillRect(154, 16, 188, 28);
                    g.setColor(new Color(255, 225, 64));
                    g.fillRect(18, 208, 324, 18);
                  } finally {
                    g.dispose();
                  }
                }
              }
            }
            """.trimIndent()
    }
}
