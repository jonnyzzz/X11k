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
        val reference = runRobotProbeAgainstXvfb()
        val actual = runRobotProbeAgainstKotlinServer(port = 6213)

        assertEquals(reference.width, actual.width, "Captured content width should match Xvfb reference")
        assertEquals(reference.height, actual.height, "Captured content height should match Xvfb reference")
        assertClose(
            expected = reference.nonBackgroundPixels,
            actual = actual.nonBackgroundPixels,
            tolerance = 0.12,
            message = "Kotlin server should expose similar non-background coverage to Xvfb; reference=$reference actual=$actual",
        )
        assertClose(
            expected = reference.averageRgb,
            actual = actual.averageRgb,
            tolerance = 0.08,
            message = "Kotlin server should expose similar average RGB to Xvfb; reference=$reference actual=$actual",
        )
        val sampleTolerance = 36
        for (point in VisualProbeSamplePoints) {
            val referencePixel = reference.sampleArgb.getValue(point)
            val actualPixel = actual.sampleArgb.getValue(point)
            assertTrue(
                rgbDistance(referencePixel, actualPixel) <= sampleTolerance,
                "Sample $point differs too much from Xvfb: reference=${referencePixel.hexArgb()} actual=${actualPixel.hexArgb()}\nreference=$reference\nactual=$actual",
            )
        }
        assertTrue(
            imageDistance(reference.image, actual.image) <= 18.0,
            "Kotlin server screenshot should stay visually close to Xvfb reference\nreference=$reference\nactual=$actual",
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

    private fun runRobotProbeAgainstXvfb(): VisualProbeCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileProbe(container, "VisualParityProbe", VisualParityProbeSource)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 java -cp /tmp -Djava.awt.headless=false -Dsun.java2d.xrender=True -Dsun.java2d.opengl=false VisualParityProbe
                    status=${'$'}?
                    kill "${'$'}xvfb" 2>/dev/null || true
                    exit "${'$'}status"
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualProbeCapture(result.stdout)
            }
    }

    private fun runRobotProbeAgainstKotlinServer(port: Int): VisualProbeCapture {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileProbe(container, "VisualParityProbe", VisualParityProbeSource)
                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        "DISPLAY=host.docker.internal:$display java -cp /tmp -Djava.awt.headless=false -Dsun.java2d.xrender=True -Dsun.java2d.opengl=false VisualParityProbe",
                    )
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    return visualProbeCapture(result.stdout)
                }
        }
    }

    private fun compileProbe(container: GenericContainer<*>, mainClass: String, source: String) {
        val sourceResult = container.execInContainer("sh", "-lc", "cat > /tmp/$mainClass.java <<'JAVA'\n$source\nJAVA\njavac /tmp/$mainClass.java")
        assertEquals(0, sourceResult.exitCode, sourceResult.stderr + sourceResult.stdout)
    }

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*data-window-id="([^"]+)"[^>]*href="data:image/png;base64,([A-Za-z0-9+/=]+)"""")
            .findAll(svg)
            .map { EmbeddedPng(it.groupValues[1], Base64.getDecoder().decode(it.groupValues[2])) }
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
        val samples = VisualProbeSamplePoints.associateWith { point -> image.getRGB(point.first, point.second) }
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

    private fun waitUntil(condition: () -> Boolean) {
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
        assertTrue(condition(), "Condition did not become true before timeout")
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
            300 to 186,
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
    }
}
