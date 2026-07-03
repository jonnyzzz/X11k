package org.jonnyzzz.xserver

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XvfbContainerTest {
    @Test
    fun `docker baseline can run xdpyinfo against xvfb`() {
        assumeDockerAndImage(REFERENCE_IMAGE)

        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "300")
            .use { container ->
                container.start()
                val result = container.execInContainer(
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
    fun `xlogo robot screenshot and svg framebuffer roughly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXlogoAgainstXvfb()
        val actual = runXlogoAgainstKotlinServer(port = 6226)

        assertTrue(actual.text.contains("label=\"xlogo\""), actual.text)
        assertTrue(actual.text.contains("- FillPoly:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xlogo SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertXlogoCaptureClose(reference, actual.robot, label = "Kotlin xlogo Robot screenshot")
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = XlogoCaptureWidth,
            ownerHeight = XlogoCaptureHeight,
            label = "Kotlin xlogo composed SVG framebuffer",
        )
    }

    @Test
    fun `window manager smoke exposes independent windows and overlap over http`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        val port = 6209
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()

                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        export DISPLAY=host.docker.internal:$display
                        twm >/tmp/twm.log 2>&1 &
                        sleep 1
                        timeout 6s xlogo -geometry 180x120+40+40 >/tmp/xlogo.log 2>&1 &
                        timeout 6s xclock -geometry 180x120+110+90 >/tmp/xclock.log 2>&1 &
                        sleep 2
                        curl -fsS http://host.docker.internal:$port/text.txt > /tmp/screen.txt
                        curl -fsS http://host.docker.internal:$port/screen.svg > /tmp/screen.svg
                        cat /tmp/screen.txt
                        printf '\n--- SVG ---\n'
                        cat /tmp/screen.svg
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    assertTrue(result.stdout.contains("Focus:"), result.stdout)
                    assertTrue(result.stdout.contains("Overlap and focus:"), result.stdout)
                    assertTrue(result.stdout.contains("overlaps"), result.stdout)
                    assertTrue(result.stdout.contains("data-window-id="), result.stdout)
                }

            server.close()
            serverThread.join(1_000)
        }
    }

    private fun assertClientSucceeds(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainer(
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
        val result = container.execInContainer(
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
                val result = container.execInContainer(
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
                    DISPLAY=:99 xlogo -geometry ${XlogoCaptureWidth}x${XlogoCaptureHeight}+${XlogoCaptureX}+${XlogoCaptureY} >/tmp/xlogo.log 2>&1 &
                    logo=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout)
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
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        xlogo -geometry ${XlogoCaptureWidth}x${XlogoCaptureHeight}+${XlogoCaptureX}+${XlogoCaptureY} >/tmp/xlogo.log 2>&1 &
                        echo ${'$'}! >/tmp/xlogo.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/xlogo.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xlogo did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xlogo\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainer(
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
                    container.execInContainer("sh", "-lc", "kill $(cat /tmp/xlogo.pid) 2>/dev/null || true")
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return XlogoResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = pngDataUris(svg),
                    )
                }
        }
    }

    private fun compileRobotCapture(container: GenericContainer<*>) {
        val result = container.execInContainer(
            "sh",
            "-lc",
            "cat > /tmp/XRobotCapture.java <<'JAVA'\n$RobotCaptureSource\nJAVA\njavac /tmp/XRobotCapture.java",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun assertXlogoCaptureClose(expected: VisualCapture, actual: VisualCapture, label: String) {
        assertEquals(expected.width, actual.width, "$label width should match Xvfb reference")
        assertEquals(expected.height, actual.height, "$label height should match Xvfb reference")
        assertClose(
            expected = expected.nonBackgroundPixels,
            actual = actual.nonBackgroundPixels,
            tolerance = 0.08,
            message = "$label should expose similar non-background coverage to Xvfb; reference=$expected actual=$actual",
        )
        assertClose(
            expected = expected.averageRgb,
            actual = actual.averageRgb,
            tolerance = 0.04,
            message = "$label should expose similar average RGB to Xvfb; reference=$expected actual=$actual",
        )
        val distance = imageDistance(expected.image, actual.image)
        assertTrue(
            distance <= 45.0,
            "$label should stay visually close to Xvfb reference; distance=$distance\nreference=$expected\nactual=$actual",
        )
    }

    private fun assertComposedSvgClose(
        expected: VisualCapture,
        embeddedFramebuffers: List<EmbeddedPng>,
        ownerWidth: Int,
        ownerHeight: Int,
        label: String,
    ) {
        val owner = embeddedFramebuffers.lastOrNull { it.width == ownerWidth && it.height == ownerHeight }
            ?: error("$label did not include an owner framebuffer ${ownerWidth}x$ownerHeight; exported=$embeddedFramebuffers")
        val composed = composeEmbeddedFramebuffers(embeddedFramebuffers)
        val cropped = composed.getSubimage(owner.x, owner.y, expected.width, expected.height)
        assertXlogoCaptureClose(
            expected = expected,
            actual = visualCapture(cropped),
            label = label,
        )
    }

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                val tag = match.value
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val x = svgImageAttribute(tag, "x") ?: return@mapNotNull null
                val y = svgImageAttribute(tag, "y") ?: return@mapNotNull null
                val width = svgImageAttribute(tag, "width") ?: return@mapNotNull null
                val height = svgImageAttribute(tag, "height") ?: return@mapNotNull null
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

    private fun svgImageAttribute(tag: String, name: String): Int? =
        Regex("""\b$name="(-?\d+)"""").find(tag)?.groupValues?.get(1)?.toInt()

    private fun composeEmbeddedFramebuffers(embeddedFramebuffers: List<EmbeddedPng>): BufferedImage {
        val width = embeddedFramebuffers.maxOfOrNull { it.x + it.width } ?: 0
        val height = embeddedFramebuffers.maxOfOrNull { it.y + it.height } ?: 0
        require(width > 0 && height > 0) { "No framebuffer geometry to compose: $embeddedFramebuffers" }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            for (embedded in embeddedFramebuffers) {
                val layer = ImageIO.read(ByteArrayInputStream(embedded.bytes)) ?: continue
                graphics.drawImage(layer, embedded.x, embedded.y, embedded.width, embedded.height, null)
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
                if (rgbDistance(argb, XlogoBackground) > 8) nonBackground++
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

    private companion object {
        const val CLIENT_IMAGE = "jonnyzzz-x/x11-client:latest"
        const val REFERENCE_IMAGE = "jonnyzzz-x/x11-reference:latest"
        const val XlogoCaptureX = 20
        const val XlogoCaptureY = 20
        const val XlogoCaptureWidth = 220
        const val XlogoCaptureHeight = 160
        const val XlogoBackground = 0xffff_ffff.toInt()

        val RobotCaptureSource = """
            import java.awt.Rectangle;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;

            public class XRobotCapture {
              public static void main(String[] args) throws Exception {
                Thread.sleep(1200);
                BufferedImage image = new Robot().createScreenCapture(
                    new Rectangle($XlogoCaptureX, $XlogoCaptureY, $XlogoCaptureWidth, $XlogoCaptureHeight));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
              }
            }
        """.trimIndent()
    }

    private data class EmbeddedPng(
        val id: String,
        val bytes: ByteArray,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
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

    private data class XlogoResult(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val embeddedFramebuffers: List<EmbeddedPng>,
    )
}
