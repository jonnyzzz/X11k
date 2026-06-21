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
        assertContains(result.svg, """class="framebuffer-image"""")
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
                            httpGet(port, "/screen.svg").contains("""class="framebuffer-image"""")
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

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*data-window-id="([^"]+)"[^>]*href="data:image/png;base64,([A-Za-z0-9+/=]+)"""")
            .findAll(svg)
            .map { EmbeddedPng(it.groupValues[1], Base64.getDecoder().decode(it.groupValues[2])) }
            .toList()

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
    }
}
