package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.utility.DockerImageName
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import java.net.Socket
import java.nio.file.Path
import java.net.ServerSocket
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntellijCommunitySmokeTest {
    @Test
    fun `intellij smoke svg png parser is attribute-order independent`() {
        val svg =
            """
            <svg>
              <image data-window-id="0x20" x="12" y="24" width="10" height="10" href="data:image/png;base64,aGVsbG8="/>
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
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withFileSystemBind(projectRoot().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
                .withCommand("sleep", "900")
                .use { container ->
                    container.start()
                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        command -v run-intellij
                        command -v git
                        check() {
                          echo "check: ${'$'}*"
                          "${'$'}@"
                        }
                        if [ -n "${url.orEmpty()}" ]; then
                          export IDEA_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display \
                        IDEA_PROJECT=/workspace/jonnyzzz-x \
                        IDEA_TRUST_PROJECT=true \
                        run-intellij >/tmp/idea-run-smoke.log 2>&1 &
                        pid=${'$'}!
                        echo "${'$'}pid" >/tmp/idea-smoke.pid
                        opened=0
                        for _ in ${'$'}(seq 1 120); do
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
                        check grep -q 'experimental.ui.on.first.startup' /tmp/idea-config/options/other.xml
                        check sh -lc 'find /root/.java/.userPrefs/jetbrains -name prefs.xml -exec grep -l "euacommunity_accepted_version" {} + | grep -q .'
                        check grep -q -- "-Didea.trust.all.projects=true" /tmp/idea-extra.vmoptions
                        check grep -q "componentStore=/workspace/jonnyzzz-x" /tmp/idea-log/idea.log
                        if grep -q "Download JDK" /tmp/idea-log/idea.log; then echo "unexpected Download JDK log"; exit 1; fi
                        if grep -q "Cannot Run Git" /tmp/idea-log/idea.log; then echo "unexpected Cannot Run Git log"; exit 1; fi
                        if grep -q "Project is not trusted" /tmp/idea-log/idea.log; then echo "unexpected Project is not trusted log"; exit 1; fi
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    assertFalse(
                        container.execInContainer("sh", "-lc", "grep -q 'Project is not trusted' /tmp/idea-log/idea.log").exitCode == 0,
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
                        container.execInContainer("sh", "-lc", "kill $(cat /tmp/idea-smoke.pid 2>/dev/null || pgrep -f run-intellij) 2>/dev/null || true")
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
        assumeTrue(imageExists(clientImage), "Build $clientImage first with scripts/run-gradle-bounded.sh dockerBuildX11Client")
        assumeTrue(imageExists(referenceImage), "Build $referenceImage first with scripts/run-gradle-bounded.sh dockerBuildX11Images")

        val reference = runIntellijAgainstXvfb(referenceImage, url)
        val actual = runIntellijAgainstKotlinServer(port, clientImage, url)

        assertTrue(actual.text.contains("Content window"), actual.text)
        assertTrue(actual.text.contains("Unsupported requests:\n- None."), actual.text)
        assertFalse(actual.text.contains("Download SDK") || actual.text.contains("Download JDK"), actual.text)
        assertIntellijVisualClose(reference, actual.robot, "Kotlin Robot IntelliJ capture")

        val composedSvg = composeSvgLayers(actual.svgLayers, IntellijCaptureWidth, IntellijCaptureHeight)
        assertIntellijVisualClose(reference, visualCapture(composedSvg), "Kotlin SVG-composed IntelliJ framebuffer")
    }

    private fun projectRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

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

    private fun runIntellijAgainstXvfb(image: String, url: String?): VisualCapture =
        GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
            .withFileSystemBind(projectRoot().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
            .withCommand("sleep", "900")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    command -v Xvfb
                    command -v run-intellij
                    command -v git
                    if [ -n "${url.orEmpty()}" ]; then
                      export IDEA_URL="${url.orEmpty()}"
                    fi
                    Xvfb :99 -screen 0 ${IntellijCaptureWidth}x${IntellijCaptureHeight}x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}idea" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 80); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xsetroot -solid white >/tmp/xsetroot.log 2>&1 || true
                    DISPLAY=:99 \
                    IDEA_PROJECT=/workspace/jonnyzzz-x \
                    IDEA_TRUST_PROJECT=true \
                    run-intellij >/tmp/idea-run-xvfb.log 2>&1 &
                    idea=${'$'}!
                    opened=0
                    for _ in ${'$'}(seq 1 120); do
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
                    sleep 5
                    DISPLAY=:99 java -cp /tmp XIntellijRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                visualCapture(result.stdout)
            }

    private fun runIntellijAgainstKotlinServer(port: Int, image: String, url: String?): IntellijKotlinCapture {
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = IntellijCaptureWidth,
                height = IntellijCaptureHeight,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withFileSystemBind(projectRoot().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
                .withCommand("sleep", "900")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        command -v run-intellij
                        command -v git
                        if [ -n "${url.orEmpty()}" ]; then
                          export IDEA_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display \
                        IDEA_PROJECT=/workspace/jonnyzzz-x \
                        IDEA_TRUST_PROJECT=true \
                        run-intellij >/tmp/idea-run-parity.log 2>&1 &
                        pid=${'$'}!
                        echo "${'$'}pid" >/tmp/idea-parity.pid
                        opened=0
                        for _ in ${'$'}(seq 1 120); do
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
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)
                    try {
                        val snapshot = waitForVisibleIntellijPixels(port)
                        Thread.sleep(5_000)
                        val capture = container.execInContainer(
                            "sh",
                            "-lc",
                            """
                            set -eu
                            pid=${'$'}(cat /tmp/idea-parity.pid)
                            kill -0 "${'$'}pid"
                            DISPLAY=host.docker.internal:$display java -cp /tmp XIntellijRobotCapture
                            """.trimIndent(),
                        )
                        assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                        val svg = httpGet(port, "/screen.svg")
                        return IntellijKotlinCapture(
                            robot = visualCapture(capture.stdout),
                            text = snapshot.text,
                            svgLayers = svgCompositionLayers(svg),
                        )
                    } finally {
                        container.execInContainer("sh", "-lc", "kill $(cat /tmp/idea-parity.pid 2>/dev/null || pgrep -f run-intellij) 2>/dev/null || true")
                        server.close()
                        serverThread.join(1_000)
                    }
                }
        }
    }

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
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
        return Regex("""<(?:image|rect)\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
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
    }

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
        val result = container.execInContainer(
            "sh",
            "-lc",
            "cat > /tmp/XIntellijRobotCapture.java <<'JAVA'\n${robotCaptureSource()}\nJAVA\njavac /tmp/XIntellijRobotCapture.java",
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
            Thread.sleep(1200);
            BufferedImage image = robot.createScreenCapture(
                new Rectangle(0, 0, $IntellijCaptureWidth, $IntellijCaptureHeight));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
          }
        }
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

    private data class IntellijKotlinCapture(
        val robot: VisualCapture,
        val text: String,
        val svgLayers: List<SvgLayer>,
    )

    private data class SvgLayer(
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
    }
}
