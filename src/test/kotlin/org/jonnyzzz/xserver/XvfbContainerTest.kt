package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DockerExecTimeoutSeconds = 45L

private fun xvfbClientArtifactsDirectoryFile(): File {
    val retainedRoot = System.getProperty("x.guiArtifactsDir") ?: System.getenv("X_GUI_ARTIFACTS_DIR")
    return if (retainedRoot.isNullOrBlank()) {
        File("build/tmp/xvfb-container-test")
    } else {
        File(retainedRoot, "xvfb-container-test")
    }
}

class XvfbContainerTest {
    @Test
    fun `unsupported request guard rejects mixed inventory`() {
        val failure = assertFailsWith<AssertionError> {
            assertNoUnsupportedRequests(
                "Unsupported requests:\n- None.\n- opcode=127 minor=3\n\nRENDER operations:\n- None.",
                "fixture",
            )
        }

        assertTrue(failure.message.orEmpty().contains("opcode=127"), failure.message)
    }

    @Test
    fun `reference validation rejects black root with partial white fragment`() {
        val image = BufferedImage(RealClientCaptureWidth, RealClientCaptureHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, 160, 90)
        } finally {
            graphics.dispose()
        }

        val capture = visualCapture(image)
        assertTrue(capture.nonBackgroundPixels >= 12_000)
        assertTrue(distinctColorCount(image, limit = 2) >= 2)
        for (fixture in listOf("xterm", "xlogo", "xclock", "xeyes", "xcalc")) {
            val failure = assertFailsWith<AssertionError> { assertValidReferenceCapture(capture, fixture) }
            assertTrue(failure.message.orEmpty().contains("reference"), failure.message)
        }
    }

    @Test
    fun `visual metrics include signed mismatch delta histograms`() {
        val expected = BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB)
        val actual = BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB)
        expected.setRGB(0, 0, 0xff05_0505.toInt())
        actual.setRGB(0, 0, 0xff04_0404.toInt())
        expected.setRGB(1, 0, 0xff21_2121.toInt())
        actual.setRGB(1, 0, 0xff20_2020.toInt())
        expected.setRGB(2, 0, 0xff00_00ff.toInt())
        actual.setRGB(2, 0, 0xff00_00fe.toInt())

        assertEquals("-1,-1,-1,0:2 0,0,-1,0:1", mismatchDeltaHistogram(expected, actual))
        assertEquals("-1:2", grayMismatchDeltaHistogram(expected, actual))
    }

    @Test
    fun `svg composition parser preserves visible group override under hidden parent`() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        val layers = svgCompositionLayers(
            """
            <svg>
              <g visibility="hidden">
                <image class="framebuffer-image" data-window-id="0xhidden" x="0" y="0" width="1" height="1" href="data:image/png;base64,$png"/>
                <g visibility="visible">
                  <image class="framebuffer-image" data-window-id="0xvisible" x="2" y="0" width="1" height="1" href="data:image/png;base64,$png"/>
                </g>
              </g>
            </svg>
            """.trimIndent(),
        )

        assertEquals(listOf("0xvisible"), layers.map { it.id })
        assertEquals(2, layers.single().x)
    }

    @Test
    fun `docker baseline can run xdpyinfo against xvfb`() {
        assumeDockerAndImage(REFERENCE_IMAGE)

        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "300")
            .use { container ->
                container.start()
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    "Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 & " +
                        "for i in $(seq 1 50); do DISPLAY=:99 xdpyinfo >/tmp/xdpyinfo.log 2>&1 && break; sleep 0.1; done; " +
                        "cat /tmp/xdpyinfo.log",
                )
                assertEquals(0, result.exitCode, result.stderr)
                assertTrue(result.stdout.contains("dimensions:    640x480 pixels"), result.stdout)
            }
    }

    @Test
    fun `docker x11 tools can query kotlin server`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        val port = 6207
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()

                    assertClientSucceeds(container, port, "xdpyinfo")
                    assertClientSucceeds(container, port, "xwininfo -root")
                    assertClientSucceeds(container, port, "xprop -root")
                    assertClientSucceeds(container, port, "xset q")
                    assertClientKeepsRunning(container, port, "xlogo")
                    assertClientKeepsRunning(container, port, "xclock")
                    assertClientKeepsRunning(container, port, "xeyes")
                    assertClientKeepsRunning(container, port, "xcalc")
                    assertClientKeepsRunning(container, port, "xterm")
                }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `xlogo robot screenshot and svg framebuffer exactly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXlogoAgainstXvfb()
        val actual = runXlogoAgainstKotlinServer(port = 6226)
        dumpRealClientArtifacts("xlogo", actual)

        assertTrue(actual.text.contains("label=\"xlogo\""), actual.text)
        assertTrue(actual.text.contains("- FillPoly:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xlogo SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(reference, actual.robot, label = "Kotlin xlogo Robot screenshot")
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xlogo composed SVG framebuffer",
        )
    }

    @Test
    fun `xclock robot screenshot and svg framebuffer exactly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXclockAgainstXvfb()
        val actual = runXclockAgainstKotlinServer(port = 6227)
        dumpRealClientArtifacts("xclock", actual)

        assertTrue(actual.text.contains("label=\"xclock\""), actual.text)
        assertTrue(actual.text.contains("- PutImage:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xclock SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xclock Robot screenshot",
        )
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xclock composed SVG framebuffer",
        )
    }

    @Test
    fun `xcalc robot screenshot and svg framebuffer exactly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXcalcAgainstXvfb()
        val actual = runXcalcAgainstKotlinServer(port = 6228)
        dumpRealClientArtifacts("xcalc", actual)

        assertTrue(actual.text.contains("label=\"Calculator\""), actual.text)
        assertTrue(
            actual.text.contains("- PolyText8:") ||
                actual.text.contains("- ImageText8:") ||
                actual.text.contains("- PutImage:"),
            actual.text,
        )
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xcalc SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xcalc Robot screenshot",
        )
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xcalc composed SVG framebuffer",
        )
    }

    @Test
    fun `xeyes robot screenshot and svg framebuffer exactly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXeyesAgainstXvfb()
        val actual = runXeyesAgainstKotlinServer(port = 6229)
        dumpRealClientArtifacts("xeyes", actual)

        assertTrue(actual.text.contains("label=\"xeyes\""), actual.text)
        assertTrue(actual.text.contains("- RENDER.Trapezoids:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xeyes SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xeyes Robot screenshot",
        )
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xeyes composed SVG framebuffer",
        )
    }

    @Test
    fun `xterm robot screenshot and svg framebuffer exactly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXtermAgainstXvfb()
        val actual = runXtermAgainstKotlinServer(port = 6230)
        dumpRealClientArtifacts("xterm", actual)

        assertTrue(actual.text.contains("label=\"xterm-parity\""), actual.text)
        assertTrue(
            actual.text.contains("- PolyText8:") ||
                actual.text.contains("- ImageText8:") ||
                actual.text.contains("- PutImage:"),
            actual.text,
        )
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xterm SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xterm Robot screenshot",
        )
        assertComposedSvgCaptureClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            captureX = RealClientCaptureX,
            captureY = RealClientCaptureY,
            captureWidth = RealClientCaptureWidth,
            captureHeight = RealClientCaptureHeight,
            backgroundPixel = 0xff00_0000.toInt(),
            label = "Kotlin xterm composed SVG framebuffer",
        )
    }

    @Test
    fun `window manager smoke exposes independent windows and overlap over http`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runWindowManagerAgainstXvfb()
        val actual = runWindowManagerAgainstKotlinServer(port = 6209)
        dumpRealClientArtifacts("window-manager", actual)

        assertTrue(actual.text.contains("Focus:"), actual.text)
        assertTrue(actual.text.contains("Overlap and focus:"), actual.text)
        assertTrue(actual.text.contains("overlaps"), actual.text)
        assertTrue(actual.text.contains("label=\"xlogo\""), actual.text)
        assertTrue(actual.text.contains("label=\"xclock\""), actual.text)
        assertTrue(actual.svg.contains("data-window-id="), actual.svg)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected WM SVG export to retain app framebuffers\n${actual.svg}\n${actual.text}")
        assertTrue(actual.svg.hasSvgClass("window-border"), "Expected WM SVG export to retain window-manager border layers\n${actual.svg}\n${actual.text}")
        val actualComposedSvg = windowManagerComposedSvgCapture(actual.embeddedFramebuffers)
        assertWindowManagerAppContentVisible(reference, label = "Xvfb twm reference")
        assertWindowManagerAppContentVisible(actual.robot, label = "Kotlin twm Robot screenshot")
        assertWindowManagerAppContentVisible(actualComposedSvg, label = "Kotlin twm composed SVG framebuffer")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin twm composed Robot screenshot",
        )
        assertVisualCaptureClose(
            expected = reference,
            actual = actualComposedSvg,
            label = "Kotlin twm composed SVG framebuffer",
        )
    }

    private fun runWindowManagerAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(
                    container,
                    captureX = WindowManagerCaptureX,
                    captureY = WindowManagerCaptureY,
                    captureWidth = WindowManagerCaptureWidth,
                    captureHeight = WindowManagerCaptureHeight,
                )
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 800x600x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}clock" "${'$'}logo" "${'$'}twm" "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 twm >/tmp/twm.log 2>&1 &
                    twm=${'$'}!
                    sleep 1
                    DISPLAY=:99 xlogo -geometry ${WindowManagerXlogoGeometry} >/tmp/xlogo.log 2>&1 &
                    logo=${'$'}!
                    DISPLAY=:99 ${windowManagerXclockCommand()} >/tmp/xclock.log 2>&1 &
                    clock=${'$'}!
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xwininfo -name xlogo >/tmp/xlogo-ready.log 2>&1 &&
                        DISPLAY=:99 xwininfo -name xclock >/tmp/xclock-ready.log 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xwininfo -name xlogo >/tmp/xlogo-ready.log 2>&1
                    DISPLAY=:99 xwininfo -name xclock >/tmp/xclock-ready.log 2>&1
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout).also { assertValidReferenceCapture(it, "twm") }
            }
    }

    private fun runWindowManagerAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = 800,
                height = 600,
                rootBackgroundPixel = WindowManagerBackground and 0x00ff_ffff,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()
                    compileRobotCapture(
                        container,
                        captureX = WindowManagerCaptureX,
                        captureY = WindowManagerCaptureY,
                        captureWidth = WindowManagerCaptureWidth,
                        captureHeight = WindowManagerCaptureHeight,
                    )

                    val display = port - 6000
                    val result = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        export DISPLAY=host.docker.internal:$display
                        twm >/tmp/twm.log 2>&1 &
                        echo ${'$'}! >/tmp/twm.pid
                        sleep 1
                        xlogo -geometry ${WindowManagerXlogoGeometry} >/tmp/xlogo.log 2>&1 &
                        echo ${'$'}! >/tmp/xlogo.pid
                        ${windowManagerXclockCommand()} >/tmp/xclock.log 2>&1 &
                        echo ${'$'}! >/tmp/xclock.pid
                        sleep 2
                        curl -fsS http://host.docker.internal:$port/text.txt > /tmp/screen.txt
                        curl -fsS http://host.docker.internal:$port/screen.svg > /tmp/screen.svg
                        java -cp /tmp XRobotCapture > /tmp/robot.txt
                        cat /tmp/screen.txt
                        printf '\n--- SVG ---\n'
                        cat /tmp/screen.svg
                        printf '\n--- ROBOT ---\n'
                        cat /tmp/robot.txt
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    xvfbContainerArtifactsDirectory().let { directory ->
                        File(directory, "window-manager-actual.txt").writeText(text)
                        File(directory, "window-manager-actual.svg").writeText(svg)
                    }
                    stopClientProcesses(
                        container = container,
                        port = port,
                        label = "window-manager",
                        pidFiles = arrayOf("/tmp/xclock.pid", "/tmp/xlogo.pid", "/tmp/twm.pid"),
                    )
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    return RealClientResult(
                        robot = visualCapture(result.stdout.substringAfter("--- ROBOT ---")),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun windowManagerComposedSvgCapture(embeddedFramebuffers: List<EmbeddedPng>): VisualCapture {
        val composed = composeEmbeddedFramebuffers(
            embeddedFramebuffers = embeddedFramebuffers,
            canvasWidth = WindowManagerCaptureX + WindowManagerCaptureWidth,
            canvasHeight = WindowManagerCaptureY + WindowManagerCaptureHeight,
            backgroundPixel = WindowManagerBackground,
        )
        return visualCapture(
            composed.getSubimage(
                WindowManagerCaptureX,
                WindowManagerCaptureY,
                WindowManagerCaptureWidth,
                WindowManagerCaptureHeight,
            ),
        )
    }

    private fun assertWindowManagerAppContentVisible(capture: VisualCapture, label: String) {
        val xlogoDarkPixels = darkPixelsInRootRectangle(capture.image, rootX = 55, rootY = 65, width = 45, height = 80)
        val xlogoLightPixels = lightPixelsInRootRectangle(capture.image, rootX = 55, rootY = 65, width = 45, height = 80)
        val xclockDarkPixels = darkPixelsInRootRectangle(capture.image, rootX = 130, rootY = 125, width = 120, height = 85)
        val xclockLightPixels = lightPixelsInRootRectangle(capture.image, rootX = 130, rootY = 125, width = 120, height = 85)
        assertTrue(
            xlogoDarkPixels >= 120,
            "$label should include xlogo line art; darkPixels=$xlogoDarkPixels capture=$capture",
        )
        assertTrue(
            xlogoLightPixels >= 2_500,
            "$label should include the light xlogo surface; lightPixels=$xlogoLightPixels capture=$capture",
        )
        assertTrue(
            xclockDarkPixels >= 120,
            "$label should include xclock ticks or hands; darkPixels=$xclockDarkPixels capture=$capture",
        )
        assertTrue(
            xclockLightPixels >= 8_000,
            "$label should include the light xclock surface; lightPixels=$xclockLightPixels capture=$capture",
        )
    }

    private fun darkPixelsInRootRectangle(
        image: BufferedImage,
        rootX: Int,
        rootY: Int,
        width: Int,
        height: Int,
    ): Int {
        val xStart = (rootX - WindowManagerCaptureX).coerceIn(0, image.width)
        val yStart = (rootY - WindowManagerCaptureY).coerceIn(0, image.height)
        val xEnd = (rootX - WindowManagerCaptureX + width).coerceIn(0, image.width)
        val yEnd = (rootY - WindowManagerCaptureY + height).coerceIn(0, image.height)
        return pixelCount(image, Rectangle(xStart, yStart, xEnd - xStart, yEnd - yStart), PixelTone.DARK)
    }

    private fun lightPixelsInRootRectangle(
        image: BufferedImage,
        rootX: Int,
        rootY: Int,
        width: Int,
        height: Int,
    ): Int {
        val xStart = (rootX - WindowManagerCaptureX).coerceIn(0, image.width)
        val yStart = (rootY - WindowManagerCaptureY).coerceIn(0, image.height)
        val xEnd = (rootX - WindowManagerCaptureX + width).coerceIn(0, image.width)
        val yEnd = (rootY - WindowManagerCaptureY + height).coerceIn(0, image.height)
        return pixelCount(image, Rectangle(xStart, yStart, xEnd - xStart, yEnd - yStart), PixelTone.LIGHT)
    }

    private fun assertClientSucceeds(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainerBounded(
            "sh",
            "-lc",
            "DISPLAY=host.docker.internal:$display $command",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun assertClientKeepsRunning(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainerBounded(
            "sh",
            "-lc",
            "DISPLAY=host.docker.internal:$display timeout 2s $command",
        )
        assertEquals(124, result.exitCode, result.stderr + result.stdout)
    }

    private fun runXlogoAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}logo" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xlogo -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY} >/tmp/xlogo.log 2>&1 &
                    logo=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout).also { assertValidReferenceCapture(it, "xlogo") }
            }
    }

    private fun runXlogoAgainstKotlinServer(port: Int): XlogoResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        xlogo -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY} >/tmp/xlogo.log 2>&1 &
                        echo ${'$'}! >/tmp/xlogo.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainerBounded("sh", "-lc", "cat /tmp/xlogo.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xlogo did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xlogo\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xlogo.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    stopClientProcesses(container, port, "xlogo", arrayOf("/tmp/xlogo.pid"))
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return XlogoResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXclockAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}clock" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xclockCommand()} >/tmp/xclock.log 2>&1 &
                    clock=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout).also { assertValidReferenceCapture(it, "xclock") }
            }
    }

    private fun runXclockAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xclockCommand()} >/tmp/xclock.log 2>&1 &
                        echo ${'$'}! >/tmp/xclock.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainerBounded("sh", "-lc", "cat /tmp/xclock.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xclock did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xclock\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xclock.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    stopClientProcesses(container, port, "xclock", arrayOf("/tmp/xclock.pid"))
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXcalcAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}calc" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xcalcCommand()} >/tmp/xcalc.log 2>&1 &
                    calc=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout).also { assertValidReferenceCapture(it, "xcalc") }
            }
    }

    private fun runXcalcAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xcalcCommand()} >/tmp/xcalc.log 2>&1 &
                        echo ${'$'}! >/tmp/xcalc.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainerBounded("sh", "-lc", "cat /tmp/xcalc.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xcalc did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"Calculator\"") &&
                            text.contains("- PolyText8:")
                    }

                    val capture = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xcalc.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    stopClientProcesses(container, port, "xcalc", arrayOf("/tmp/xcalc.pid"))
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXeyesAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container, movePointer = true)
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}eyes" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xeyesCommand()} >/tmp/xeyes.log 2>&1 &
                    eyes=${'$'}!
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xwininfo -name xeyes >/tmp/xeyes-ready.log 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xwininfo -name xeyes >/tmp/xeyes-ready.log 2>&1
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout).also { assertValidReferenceCapture(it, "xeyes") }
            }
    }

    private fun runXeyesAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = 640,
                height = 480,
                rootBackgroundPixel = 0x0000_0000,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container, movePointer = true)
                    val display = port - 6000
                    val startResult = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xeyesCommand()} >/tmp/xeyes.log 2>&1 &
                        echo ${'$'}! >/tmp/xeyes.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainerBounded("sh", "-lc", "cat /tmp/xeyes.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xeyes did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xeyes\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xeyes.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    xvfbContainerArtifactsDirectory().let { directory ->
                        File(directory, "xeyes-actual.txt").writeText(text)
                        File(directory, "xeyes-actual.svg").writeText(svg)
                    }
                    stopClientProcesses(container, port, "xeyes", arrayOf("/tmp/xeyes.pid"))
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXtermAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainerBounded(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}term" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xtermCommand()} >/tmp/xterm.log 2>&1 &
                    term=${'$'}!
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xwininfo -name xterm-parity >/tmp/xterm-ready.log 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xwininfo -name xterm-parity >/tmp/xterm-ready.log 2>&1
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout).also { assertValidReferenceCapture(it, "xterm") }
            }
    }

    private fun runXtermAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = 640,
                height = 480,
                rootBackgroundPixel = 0x0000_0000,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xtermCommand()} >/tmp/xterm.log 2>&1 &
                        echo ${'$'}! >/tmp/xterm.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainerBounded("sh", "-lc", "cat /tmp/xterm.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xterm did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xterm-parity\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainerBounded(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xterm.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    xvfbContainerArtifactsDirectory().let { directory ->
                        File(directory, "xterm-actual.txt").writeText(text)
                        File(directory, "xterm-actual.svg").writeText(svg)
                    }
                    stopClientProcesses(container, port, "xterm", arrayOf("/tmp/xterm.pid"))
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun compileRobotCapture(
        container: GenericContainer<*>,
        movePointer: Boolean = false,
        captureX: Int = RealClientCaptureX,
        captureY: Int = RealClientCaptureY,
        captureWidth: Int = RealClientCaptureWidth,
        captureHeight: Int = RealClientCaptureHeight,
    ) {
        val result = container.execInContainerBounded(
            "sh",
            "-lc",
            "cat > /tmp/XRobotCapture.java <<'JAVA'\n${robotCaptureSource(movePointer, captureX, captureY, captureWidth, captureHeight)}\nJAVA\njavac /tmp/XRobotCapture.java",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun stopClientProcesses(
        container: GenericContainer<*>,
        port: Int,
        label: String,
        pidFiles: Array<String>,
    ) {
        val pidFileArguments = pidFiles.joinToString(" ")
        val result = container.execInContainerBounded(
            "sh",
            "-lc",
            """
            collect_process_tree() {
              for child in ${'$'}(pgrep -P "${'$'}1" 2>/dev/null || true); do
                collect_process_tree "${'$'}child"
              done
              printf ' %s' "${'$'}1"
            }
            roots=${'$'}(cat $pidFileArguments 2>/dev/null || true)
            pids=""
            for root in ${'$'}roots; do
              pids="${'$'}pids${'$'}(collect_process_tree "${'$'}root")"
            done
            [ -z "${'$'}pids" ] || kill ${'$'}pids 2>/dev/null || true
            for _ in ${'$'}(seq 1 40); do
              live=""
              for pid in ${'$'}pids; do
                state=${'$'}(awk '{print ${'$'}3}' "/proc/${'$'}pid/stat" 2>/dev/null || true)
                if [ -n "${'$'}state" ] && [ "${'$'}state" != Z ]; then
                  live="${'$'}live ${'$'}pid:${'$'}state"
                fi
              done
              [ -z "${'$'}live" ] && exit 0
              sleep 0.05
            done
            echo "$label clients did not stop:${'$'}live" >&2
            exit 1
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
        val finalText = httpGet(port, "/text.txt")
        File(xvfbContainerArtifactsDirectory(), "${safeArtifactLabel(label)}-final-text.txt").writeText(finalText)
        assertNoUnsupportedRequests(finalText, label)
    }

    private fun assertVisualCaptureClose(
        expected: VisualCapture,
        actual: VisualCapture,
        label: String,
    ) {
        dumpVisualCapturePair(label, expected, actual)
        assertEquals(expected.width, actual.width, "$label width should match Xvfb reference")
        assertEquals(expected.height, actual.height, "$label height should match Xvfb reference")
        assertEquals(
            0L,
            pixelMismatchCount(expected.image, actual.image),
            "$label must match every Xvfb pixel; mismatchBounds=${mismatchBounds(expected.image, actual.image).toMetricString()} " +
                "sampledDistance=${imageDistance(expected.image, actual.image)}\nreference=$expected\nactual=$actual",
        )
    }

    private fun dumpVisualCapturePair(label: String, expected: VisualCapture, actual: VisualCapture) {
        val safeLabel = safeArtifactLabel(label)
        val directory = xvfbContainerArtifactsDirectory()
        ImageIO.write(expected.image, "png", File(directory, "$safeLabel-reference.png"))
        ImageIO.write(actual.image, "png", File(directory, "$safeLabel-actual.png"))
        ImageIO.write(visualDiffImage(expected.image, actual.image), "png", File(directory, "$safeLabel-diff.png"))
        File(directory, "$safeLabel-metrics.txt").writeText(
            buildString {
                appendLine("expected=$expected")
                appendLine("actual=$actual")
                appendLine("coverageRatio=${ratio(actual.nonBackgroundPixels, expected.nonBackgroundPixels)}")
                appendLine("averageRgbDelta=${abs(actual.averageRgb - expected.averageRgb)}")
                appendLine("sampledDistance=${imageDistance(expected.image, actual.image)}")
                appendLine("mismatchPixels=${pixelMismatchCount(expected.image, actual.image)}")
                appendLine("mismatchBounds=${mismatchBounds(expected.image, actual.image).toMetricString()}")
                appendLine("mismatchSamples=${mismatchSamples(expected.image, actual.image)}")
                appendLine("mismatchDeltaHistogram=${mismatchDeltaHistogram(expected.image, actual.image)}")
                appendLine("grayMismatchDeltaHistogram=${grayMismatchDeltaHistogram(expected.image, actual.image)}")
                if (safeLabel.contains("xcalc")) {
                    append(xcalcRegionMetrics(expected.image, actual.image))
                }
            },
        )
    }

    private fun xcalcRegionMetrics(expected: BufferedImage, actual: BufferedImage): String =
        buildString {
            appendLine("regionMetrics:")
            for ((name, region) in XcalcDiagnosticRegions) {
                val clipped = Rectangle(
                    region.x,
                    region.y,
                    minOf(region.width, expected.width - region.x, actual.width - region.x),
                    minOf(region.height, expected.height - region.y, actual.height - region.y),
                )
                if (clipped.width <= 0 || clipped.height <= 0) continue
                val expectedRegion = visualCapture(expected.getSubimage(clipped.x, clipped.y, clipped.width, clipped.height))
                val actualRegion = visualCapture(actual.getSubimage(clipped.x, clipped.y, clipped.width, clipped.height))
                appendLine(
                    "$name=${clipped.x},${clipped.y} ${clipped.width}x${clipped.height} " +
                        "coverageRatio=${ratio(actualRegion.nonBackgroundPixels, expectedRegion.nonBackgroundPixels)} " +
                        "averageRgbDelta=${abs(actualRegion.averageRgb - expectedRegion.averageRgb)} " +
                        "sampledDistance=${imageDistance(expectedRegion.image, actualRegion.image)} " +
                        "mismatchBounds=${mismatchBounds(expectedRegion.image, actualRegion.image).toMetricString()}",
                )
            }
        }

    private fun dumpRealClientArtifacts(label: String, actual: RealClientResult) {
        val safeLabel = safeArtifactLabel(label)
        val directory = xvfbContainerArtifactsDirectory()
        File(directory, "$safeLabel-actual.txt").writeText(actual.text)
        File(directory, "$safeLabel-actual.svg").writeText(actual.svg)
        File(directory, "$safeLabel-svg-layers.txt").writeText(svgLayerInventory(actual.embeddedFramebuffers))
        ImageIO.write(actual.robot.image, "png", File(directory, "$safeLabel-kotlin-robot.png"))
        File(directory, "$safeLabel-kotlin-metrics.txt").writeText(
            buildString {
                appendLine("robot=${actual.robot}")
                appendLine("embeddedFramebuffers=${actual.embeddedFramebuffers.size}")
            },
        )
    }

    private fun dumpRealClientArtifacts(label: String, actual: XlogoResult) {
        val safeLabel = safeArtifactLabel(label)
        val directory = xvfbContainerArtifactsDirectory()
        File(directory, "$safeLabel-actual.txt").writeText(actual.text)
        File(directory, "$safeLabel-actual.svg").writeText(actual.svg)
        File(directory, "$safeLabel-svg-layers.txt").writeText(svgLayerInventory(actual.embeddedFramebuffers))
        ImageIO.write(actual.robot.image, "png", File(directory, "$safeLabel-kotlin-robot.png"))
        File(directory, "$safeLabel-kotlin-metrics.txt").writeText(
            buildString {
                appendLine("robot=${actual.robot}")
                appendLine("embeddedFramebuffers=${actual.embeddedFramebuffers.size}")
            },
        )
    }

    private fun svgLayerInventory(layers: List<EmbeddedPng>): String =
        buildString {
            appendLine("count=${layers.size}")
            layers.forEachIndexed { index, layer ->
                val imageSummary = layer.bytes?.let { bytes ->
                    ImageIO.read(ByteArrayInputStream(bytes))?.let { image ->
                        "png=${image.width}x${image.height} nonBackgroundPixels=${nonBackgroundPixels(image)}"
                    }
                } ?: "fill=${layer.fill?.let { "0x${it.toUInt().toString(16).padStart(8, '0')}" } ?: "none"}"
                appendLine(
                    "$index: id=${layer.id} x=${layer.x} y=${layer.y} width=${layer.width} height=${layer.height} " +
                        "$imageSummary clips=${layer.clipRectangles.joinToString { "${it.x},${it.y} ${it.width}x${it.height}" }}",
                )
            }
        }

    private fun nonBackgroundPixels(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (rgbDistance(image.getRGB(x, y), RealClientBackground) > 8) count++
            }
        }
        return count
    }

    private fun xvfbContainerArtifactsDirectory(): File =
        xvfbClientArtifactsDirectoryFile().also { it.mkdirs() }

    private fun safeArtifactLabel(label: String): String =
        label.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    private fun ratio(actual: Int, expected: Int): Double =
        if (expected == 0) {
            if (actual == 0) 1.0 else Double.POSITIVE_INFINITY
        } else {
            actual.toDouble() / expected.toDouble()
        }

    private fun assertComposedSvgClose(
        expected: VisualCapture,
        embeddedFramebuffers: List<EmbeddedPng>,
        ownerWidth: Int,
        ownerHeight: Int,
        label: String,
    ) {
        val composed = composeEmbeddedFramebuffers(embeddedFramebuffers)
        val owner = embeddedFramebuffers.lastOrNull { it.bytes != null && it.width == ownerWidth && it.height == ownerHeight }
        if (owner == null) {
            embeddedFramebuffers.lastOrNull {
                it.bytes != null &&
                    it.x <= RealClientCaptureX &&
                    it.y <= RealClientCaptureY &&
                    it.x + it.width >= RealClientCaptureX + expected.width &&
                    it.y + it.height >= RealClientCaptureY + expected.height
            } ?: error(
                "$label did not include an owner framebuffer ${ownerWidth}x$ownerHeight or a visible root framebuffer " +
                    "covering capture ${RealClientCaptureX},${RealClientCaptureY} ${expected.width}x${expected.height}; exported=$embeddedFramebuffers",
            )
        }
        val cropX = owner?.x ?: RealClientCaptureX
        val cropY = owner?.y ?: RealClientCaptureY
        require(cropX + expected.width <= composed.width && cropY + expected.height <= composed.height) {
            "$label did not include an owner framebuffer ${ownerWidth}x$ownerHeight and composed SVG bounds " +
                "${composed.width}x${composed.height} do not cover capture " +
                "${cropX},${cropY} ${expected.width}x${expected.height}; exported=$embeddedFramebuffers"
        }
        val cropped = composed.getSubimage(cropX, cropY, expected.width, expected.height)
        assertVisualCaptureClose(
            expected = expected,
            actual = visualCapture(cropped),
            label = label,
        )
    }

    private fun assertComposedSvgCaptureClose(
        expected: VisualCapture,
        embeddedFramebuffers: List<EmbeddedPng>,
        captureX: Int,
        captureY: Int,
        captureWidth: Int,
        captureHeight: Int,
        backgroundPixel: Int,
        label: String,
    ) {
        require(expected.width == captureWidth && expected.height == captureHeight) {
            "$label expected image ${expected.width}x${expected.height} does not match capture ${captureWidth}x$captureHeight"
        }
        val composed = composeEmbeddedFramebuffers(
            embeddedFramebuffers = embeddedFramebuffers,
            canvasWidth = captureX + captureWidth,
            canvasHeight = captureY + captureHeight,
            backgroundPixel = backgroundPixel,
        )
        val cropped = composed.getSubimage(captureX, captureY, captureWidth, captureHeight)
        assertVisualCaptureClose(
            expected = expected,
            actual = visualCapture(cropped),
            label = label,
        )
    }

    private fun svgCompositionLayers(svg: String): List<EmbeddedPng> {
        val clipRectangles = svgClipRectangles(svg)
        val hiddenStack = mutableListOf<Boolean>()
        val layers = mutableListOf<EmbeddedPng>()
        Regex("""<g\b[^>]*>|</g>|<(?:image|rect)\b[^>]*>""")
            .findAll(svg)
            .forEach { match ->
                val tag = match.value
                when {
                    tag.startsWith("</") -> {
                        if (hiddenStack.isNotEmpty()) hiddenStack.removeAt(hiddenStack.lastIndex)
                    }
                    tag.startsWith("<g") -> {
                        hiddenStack += svgVisibilityHidden(tag) ?: (hiddenStack.lastOrNull() == true)
                    }
                    hiddenStack.lastOrNull() == true -> Unit
                    else -> {
                        svgCompositionLayer(tag, clipRectangles)?.let { layers += it }
                    }
                }
            }
        return layers
    }

    private fun svgCompositionLayer(tag: String, clipRectangles: Map<String, List<Rectangle>>): EmbeddedPng? {
        val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1)
        val x = svgImageAttribute(tag, "x") ?: return null
        val y = svgImageAttribute(tag, "y") ?: return null
        val width = svgImageAttribute(tag, "width") ?: return null
        val height = svgImageAttribute(tag, "height") ?: return null
        val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1)
        if (encoded == null) {
            if (!tag.hasSvgClass("window-border")) return null
            val borderId = Regex("""\bdata-border-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1)
            val fill = Regex("""\bfill="#([0-9a-fA-F]{6})"""").find(tag)?.groupValues?.get(1)?.toInt(16)
                ?: return null
            return EmbeddedPng(
                id = borderId ?: id ?: "window-border",
                bytes = null,
                fill = 0xff00_0000.toInt() or fill,
                x = x,
                y = y,
                width = width,
                height = height,
                clipRectangles = svgClipPathId(tag)?.let { clipRectangles[it] }.orEmpty(),
            )
        }
        return EmbeddedPng(
            id = id ?: "framebuffer",
            bytes = Base64.getDecoder().decode(encoded),
            fill = null,
            x = x,
            y = y,
            width = width,
            height = height,
            clipRectangles = svgClipPathId(tag)
                ?.let { clipRectangles[it] }
                ?: id?.let { clipRectangles["clip-screen-${it.removePrefix("0x")}"] }.orEmpty(),
        )
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
                        val x = svgImageAttribute(tag, "x") ?: return@mapNotNull null
                        val y = svgImageAttribute(tag, "y") ?: return@mapNotNull null
                        val width = svgImageAttribute(tag, "width") ?: return@mapNotNull null
                        val height = svgImageAttribute(tag, "height") ?: return@mapNotNull null
                        Rectangle(x, y, width, height)
                    }
                    .toList()
                match.groupValues[1] to rectangles
            }

    private fun svgImageAttribute(tag: String, name: String): Int? =
        Regex("""\b$name="(-?\d+)"""").find(tag)?.groupValues?.get(1)?.toInt()

    private fun composeEmbeddedFramebuffers(
        embeddedFramebuffers: List<EmbeddedPng>,
        canvasWidth: Int = 0,
        canvasHeight: Int = 0,
        backgroundPixel: Int? = null,
    ): BufferedImage {
        val width = maxOf(embeddedFramebuffers.maxOfOrNull { it.x + it.width } ?: 0, canvasWidth)
        val height = maxOf(embeddedFramebuffers.maxOfOrNull { it.y + it.height } ?: 0, canvasHeight)
        require(width > 0 && height > 0) { "No framebuffer geometry to compose: $embeddedFramebuffers" }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            if (backgroundPixel != null) {
                graphics.color = java.awt.Color(backgroundPixel, true)
                graphics.fillRect(0, 0, width, height)
            }
            for (embedded in embeddedFramebuffers) {
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

    private fun visualCapture(stdout: String): VisualCapture {
        val encoded = stdout.lineSequence()
            .firstOrNull { it.startsWith("PNG_BASE64=") }
            ?.removePrefix("PNG_BASE64=")
            ?: error("XRobotCapture did not print PNG_BASE64, stdout:\n$stdout")
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            ?: error("XRobotCapture PNG was not readable")
        return visualCapture(image)
    }

    private fun visualCapture(image: BufferedImage): VisualCapture {
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
                if (rgbDistance(argb, RealClientBackground) > 8) nonBackground++
            }
        }
        val pixels = image.width * image.height
        return VisualCapture(
            width = image.width,
            height = image.height,
            nonBackgroundPixels = nonBackground,
            averageRgb = (redSum + greenSum + blueSum).toDouble() / (pixels * 3.0 * 255.0),
            image = image,
        )
    }

    private fun assertValidReferenceCapture(capture: VisualCapture, label: String) {
        val minimumPaintedPixels = when (label) {
            "xclock" -> 800
            "xlogo" -> 6_000
            "xcalc" -> 7_000
            "xeyes" -> 15_000
            "xterm" -> 12_000
            "twm" -> 0
            else -> error("Missing reference-content anchor for $label")
        }
        assertTrue(
            capture.nonBackgroundPixels >= minimumPaintedPixels,
            "$label Xvfb reference is blank or partial: painted=${capture.nonBackgroundPixels} minimum=$minimumPaintedPixels capture=$capture",
        )
        assertTrue(
            distinctColorCount(capture.image, limit = 2) >= 2,
            "$label Xvfb reference must retain at least two colors: $capture",
        )
        when (label) {
            "xterm" -> {
                assertRegionPixels(capture, label, "terminal body", Rectangle(5, 50, 160, 50), PixelTone.LIGHT, 7_000)
                assertRegionPixels(capture, label, "three text rows", Rectangle(0, 0, 110, 45), PixelTone.DARK, 300)
            }
            "xlogo" -> {
                assertRegionPixels(capture, label, "upper-left arm", Rectangle(20, 0, 75, 55), PixelTone.DARK, 500, 2_600)
                assertRegionPixels(capture, label, "upper-right arm", Rectangle(125, 0, 75, 55), PixelTone.DARK, 500, 1_500)
                assertRegionPixels(capture, label, "lower-left arm", Rectangle(10, 105, 80, 55), PixelTone.DARK, 500, 1_500)
                assertRegionPixels(capture, label, "lower-right arm", Rectangle(125, 105, 80, 55), PixelTone.DARK, 500, 2_600)
            }
            "xclock" -> {
                assertRegionPixels(capture, label, "upper ticks", Rectangle(55, 0, 110, 35), PixelTone.DARK, 100, 300)
                assertRegionPixels(capture, label, "left ticks", Rectangle(35, 40, 40, 80), PixelTone.DARK, 30, 150)
                assertRegionPixels(capture, label, "right ticks", Rectangle(145, 40, 40, 80), PixelTone.DARK, 40, 180)
                assertRegionPixels(capture, label, "lower ticks", Rectangle(55, 125, 110, 35), PixelTone.DARK, 40, 150)
                assertRegionPixels(capture, label, "clock hands", Rectangle(75, 50, 80, 65), PixelTone.DARK, 250, 700)
            }
            "xeyes" -> {
                assertRegionPixels(capture, label, "left eye", Rectangle(10, 15, 85, 130), PixelTone.LIGHT, 6_500)
                assertRegionPixels(capture, label, "right eye", Rectangle(125, 15, 85, 130), PixelTone.LIGHT, 6_500)
                assertRegionPixels(capture, label, "left pupil", Rectangle(27, 85, 20, 40), PixelTone.DARK, 200)
                assertRegionPixels(capture, label, "right pupil", Rectangle(142, 85, 20, 40), PixelTone.DARK, 200)
            }
            "xcalc" -> {
                assertRegionPixels(capture, label, "display", Rectangle(4, 2, 212, 20), PixelTone.DARK, 900, 1_700)
                assertRegionPixels(capture, label, "left keypad", Rectangle(4, 27, 104, 130), PixelTone.DARK, 2_500, 4_000)
                assertRegionPixels(capture, label, "right keypad", Rectangle(112, 27, 104, 130), PixelTone.DARK, 2_300, 4_000)
            }
            "twm" -> assertWindowManagerAppContentVisible(capture, "Xvfb twm reference")
        }
    }

    private fun assertRegionPixels(
        capture: VisualCapture,
        fixture: String,
        feature: String,
        region: Rectangle,
        tone: PixelTone,
        minimum: Int,
        maximum: Int = Int.MAX_VALUE,
    ) {
        val count = pixelCount(capture.image, region, tone)
        assertTrue(
            count in minimum..maximum,
            "$fixture Xvfb reference must include $feature; ${tone.metricName}Pixels=$count " +
                "expected=$minimum..$maximum region=$region capture=$capture",
        )
    }

    private fun pixelCount(image: BufferedImage, region: Rectangle, tone: PixelTone): Int {
        val clipped = region.intersection(Rectangle(0, 0, image.width, image.height))
        if (clipped.isEmpty) return 0
        var count = 0
        for (y in clipped.y until clipped.y + clipped.height) {
            for (x in clipped.x until clipped.x + clipped.width) {
                val argb = image.getRGB(x, y)
                val componentSum = ((argb ushr 16) and 0xff) + ((argb ushr 8) and 0xff) + (argb and 0xff)
                if (tone.matches(componentSum)) count++
            }
        }
        return count
    }

    private fun distinctColorCount(image: BufferedImage, limit: Int): Int {
        val colors = hashSetOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                colors += image.getRGB(x, y)
                if (colors.size >= limit) return colors.size
            }
        }
        return colors.size
    }

    private fun imageDistance(reference: BufferedImage, actual: BufferedImage): Double {
        val width = minOf(reference.width, actual.width)
        val height = minOf(reference.height, actual.height)
        if (width == 0 || height == 0) return Double.POSITIVE_INFINITY
        var total = 0L
        var samples = 0
        for (y in 0 until height step 3) {
            for (x in 0 until width step 3) {
                total += rgbDistance(reference.getRGB(x, y), actual.getRGB(x, y))
                samples++
            }
        }
        return total.toDouble() / samples.toDouble()
    }

    private fun pixelMismatchCount(expected: BufferedImage, actual: BufferedImage): Long {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        var mismatches = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedArgb = if (x < expected.width && y < expected.height) expected.getRGB(x, y) else null
                val actualArgb = if (x < actual.width && y < actual.height) actual.getRGB(x, y) else null
                if (expectedArgb != actualArgb) mismatches++
            }
        }
        return mismatches
    }

    private fun mismatchBounds(expected: BufferedImage, actual: BufferedImage): Rectangle? {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedRgb = if (x < expected.width && y < expected.height) expected.getRGB(x, y) else null
                val actualRgb = if (x < actual.width && y < actual.height) actual.getRGB(x, y) else null
                if (expectedRgb == actualRgb) continue
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

    private fun mismatchSamples(expected: BufferedImage, actual: BufferedImage, limit: Int = 12): String {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val samples = mutableListOf<String>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedRgb = if (x < expected.width && y < expected.height) expected.getRGB(x, y) else null
                val actualRgb = if (x < actual.width && y < actual.height) actual.getRGB(x, y) else null
                if (expectedRgb == actualRgb) continue
                samples += "$x,$y expected=${expectedRgb?.hexArgb() ?: "missing"} actual=${actualRgb?.hexArgb() ?: "missing"} " +
                    "delta=${if (expectedRgb != null && actualRgb != null) rgbDistance(expectedRgb, actualRgb) else "missing"}"
                if (samples.size == limit) return samples.joinToString("; ") + "; ..."
            }
        }
        return if (samples.isEmpty()) "none" else samples.joinToString("; ")
    }

    private fun mismatchDeltaHistogram(expected: BufferedImage, actual: BufferedImage, limit: Int = 12): String {
        val counts = linkedMapOf<String, Int>()
        forEachSharedMismatch(expected, actual) { expectedRgb, actualRgb ->
            val delta = listOf(
                ((actualRgb ushr 16) and 0xff) - ((expectedRgb ushr 16) and 0xff),
                ((actualRgb ushr 8) and 0xff) - ((expectedRgb ushr 8) and 0xff),
                (actualRgb and 0xff) - (expectedRgb and 0xff),
                ((actualRgb ushr 24) and 0xff) - ((expectedRgb ushr 24) and 0xff),
            ).joinToString(",")
            counts[delta] = (counts[delta] ?: 0) + 1
        }
        return histogramString(counts, limit)
    }

    private fun grayMismatchDeltaHistogram(expected: BufferedImage, actual: BufferedImage, limit: Int = 12): String {
        val counts = linkedMapOf<String, Int>()
        forEachSharedMismatch(expected, actual) { expectedRgb, actualRgb ->
            if (!isGray(expectedRgb) || !isGray(actualRgb)) return@forEachSharedMismatch
            val delta = (actualRgb and 0xff) - (expectedRgb and 0xff)
            val key = delta.toString()
            counts[key] = (counts[key] ?: 0) + 1
        }
        return histogramString(counts, limit)
    }

    private fun forEachSharedMismatch(expected: BufferedImage, actual: BufferedImage, block: (expectedRgb: Int, actualRgb: Int) -> Unit) {
        val width = minOf(expected.width, actual.width)
        val height = minOf(expected.height, actual.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedRgb = expected.getRGB(x, y)
                val actualRgb = actual.getRGB(x, y)
                if (expectedRgb != actualRgb) block(expectedRgb, actualRgb)
            }
        }
    }

    private fun isGray(argb: Int): Boolean {
        val red = (argb ushr 16) and 0xff
        val green = (argb ushr 8) and 0xff
        val blue = argb and 0xff
        return red == green && green == blue
    }

    private fun histogramString(counts: Map<String, Int>, limit: Int): String =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .joinToString(" ") { (key, count) -> "$key:$count" }
            .ifEmpty { "none" }

    private fun visualDiffImage(expected: BufferedImage, actual: BufferedImage): BufferedImage {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedRgb = if (x < expected.width && y < expected.height) expected.getRGB(x, y) else null
                val actualRgb = if (x < actual.width && y < actual.height) actual.getRGB(x, y) else null
                image.setRGB(
                    x,
                    y,
                    when {
                        expectedRgb == null && actualRgb == null -> 0
                        expectedRgb == null -> 0x00ff00
                        actualRgb == null -> 0xff00ff
                        else -> amplifiedDiffRgb(expectedRgb, actualRgb)
                    },
                )
            }
        }
        return image
    }

    private fun amplifiedDiffRgb(expectedArgb: Int, actualArgb: Int): Int {
        val alpha = (abs(((expectedArgb ushr 24) and 0xff) - ((actualArgb ushr 24) and 0xff)) * 4).coerceAtMost(255)
        val red = (abs(((expectedArgb ushr 16) and 0xff) - ((actualArgb ushr 16) and 0xff)) * 4).coerceAtMost(255)
        val green = (abs(((expectedArgb ushr 8) and 0xff) - ((actualArgb ushr 8) and 0xff)) * 4).coerceAtMost(255)
        val blue = (abs((expectedArgb and 0xff) - (actualArgb and 0xff)) * 4).coerceAtMost(255)
        if (alpha > 0 && red == 0 && green == 0 && blue == 0) return 0xffff00
        return (red shl 16) or (green shl 8) or blue
    }

    private fun rgbDistance(left: Int, right: Int): Int =
        abs(((left ushr 16) and 0xff) - ((right ushr 16) and 0xff)) +
            abs(((left ushr 8) and 0xff) - ((right ushr 8) and 0xff)) +
            abs((left and 0xff) - (right and 0xff))

    private fun Int.hexArgb(): String = "0x${toUInt().toString(16).padStart(8, '0')}"

    private fun String.hasSvgClass(className: String): Boolean =
        Regex("""\bclass="([^"]*)"""")
            .findAll(this)
            .any { match -> match.groupValues[1].split(' ').any { it == className } }

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
        throw AssertionError(failureMessage())
    }

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun assumeDockerAndImage(image: String) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val imageExists = runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess
        assumeTrue(imageExists, "Build $image first with ./gradlew dockerBuildX11Images")
    }

    private fun GenericContainer<*>.execInContainerBounded(
        vararg command: String,
        timeoutSeconds: Long = DockerExecTimeoutSeconds,
    ): org.testcontainers.containers.Container.ExecResult {
        val commandText = command.joinToString(" ")
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bounded-docker-exec").apply { isDaemon = true }
        }
        val future = executor.submit<org.testcontainers.containers.Container.ExecResult> {
            execInContainer(*command)
        }
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            val stopFailure = runCatching { stop() }.exceptionOrNull()
            val stopMessage = stopFailure?.let { "\ncontainer.stop() failed: ${it::class.simpleName}: ${it.message}" }.orEmpty()
            throw AssertionError("Docker exec timed out after ${timeoutSeconds}s: $commandText$stopMessage", e)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun clearXvfbClientArtifacts() {
            xvfbClientArtifactsDirectoryFile().deleteRecursively()
        }

        const val CLIENT_IMAGE = "jonnyzzz-x/x11-client:latest"
        const val REFERENCE_IMAGE = "jonnyzzz-x/x11-reference:latest"
        const val RealClientCaptureX = 20
        const val RealClientCaptureY = 20
        const val RealClientCaptureWidth = 220
        const val RealClientCaptureHeight = 160
        const val RealClientBackground = 0xffff_ffff.toInt()
        val XcalcDiagnosticRegions = listOf(
            "topDisplay" to Rectangle(0, 0, RealClientCaptureWidth, 40),
            "displayFrame" to Rectangle(0, 0, RealClientCaptureWidth, 24),
            "displayText" to Rectangle(24, 0, 190, 24),
            "angleModeIndicators" to Rectangle(28, 0, 140, 36),
            "keypad" to Rectangle(0, 40, RealClientCaptureWidth, RealClientCaptureHeight - 40),
        )
        const val WindowManagerCaptureX = 20
        const val WindowManagerCaptureY = 20
        const val WindowManagerCaptureWidth = 360
        const val WindowManagerCaptureHeight = 260
        const val WindowManagerBackground = 0xff00_0000.toInt()
        const val WindowManagerXlogoGeometry = "180x120+40+40"

        fun xclockCommand(): String =
            "faketime '2020-01-01 10:10:00' xclock -analog -norender -update 60 -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY}"

        fun xcalcCommand(): String =
            "xcalc -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY}"

        fun xeyesCommand(): String =
            "xeyes -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY}"

        fun xtermCommand(): String =
            "xterm -T xterm-parity -n xterm-parity -geometry 28x8+${RealClientCaptureX}+${RealClientCaptureY} +sb +bc -fn fixed -bg white -fg black -cr black -e sh -lc 'printf \"Kotlin X11\\\\nxterm parity\\\\n0123456789\\\\n\"; sleep 60'"

        fun windowManagerXclockCommand(): String =
            "faketime '2020-01-01 10:10:00' xclock -analog -norender -update 60 -geometry 180x120+110+90"

        fun robotCaptureSource(
            movePointer: Boolean,
            captureX: Int = RealClientCaptureX,
            captureY: Int = RealClientCaptureY,
            captureWidth: Int = RealClientCaptureWidth,
            captureHeight: Int = RealClientCaptureHeight,
        ): String {
            val pointerMove = if (movePointer) {
                "robot.mouseMove(${captureX + captureWidth / 2}, ${captureY + captureHeight / 2});"
            } else {
                ""
            }
            return """
            import java.awt.Rectangle;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;

            public class XRobotCapture {
              public static void main(String[] args) throws Exception {
                Robot robot = new Robot();
                $pointerMove
                Thread.sleep(1200);
                BufferedImage image = stableCapture(
                    robot, new Rectangle($captureX, $captureY, $captureWidth, $captureHeight));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
              }

              private static BufferedImage stableCapture(Robot robot, Rectangle bounds) throws Exception {
                robot.waitForIdle();
                BufferedImage previous = robot.createScreenCapture(bounds);
                int stablePairs = 0;
                for (int attempt = 0; attempt < 20; attempt++) {
                  Thread.sleep(50);
                  robot.waitForIdle();
                  BufferedImage current = robot.createScreenCapture(bounds);
                  if (samePixels(previous, current)) {
                    stablePairs++;
                    if (stablePairs >= 2) return current;
                  } else {
                    stablePairs = 0;
                  }
                  previous = current;
                }
                throw new IllegalStateException("Robot capture did not stabilize for " + bounds);
              }

              private static boolean samePixels(BufferedImage left, BufferedImage right) {
                if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) return false;
                for (int y = 0; y < left.getHeight(); y++) {
                  for (int x = 0; x < left.getWidth(); x++) {
                    if (left.getRGB(x, y) != right.getRGB(x, y)) return false;
                  }
                }
                return true;
              }
            }
            """.trimIndent()
        }
    }

    private data class EmbeddedPng(
        val id: String,
        val bytes: ByteArray?,
        val fill: Int?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val clipRectangles: List<Rectangle>,
    )

    private data class VisualCapture(
        val width: Int,
        val height: Int,
        val nonBackgroundPixels: Int,
        val averageRgb: Double,
        val image: BufferedImage,
    ) {
        override fun toString(): String =
            "VisualCapture(width=$width, height=$height, nonBackgroundPixels=$nonBackgroundPixels, averageRgb=$averageRgb)"
    }

    private enum class PixelTone(val metricName: String) {
        DARK("dark") {
            override fun matches(componentSum: Int): Boolean = componentSum < 90
        },
        LIGHT("light") {
            override fun matches(componentSum: Int): Boolean = componentSum > 720
        },
        ;

        abstract fun matches(componentSum: Int): Boolean
    }

    private data class XlogoResult(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val embeddedFramebuffers: List<EmbeddedPng>,
    )

    private data class RealClientResult(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val embeddedFramebuffers: List<EmbeddedPng>,
    )
}
