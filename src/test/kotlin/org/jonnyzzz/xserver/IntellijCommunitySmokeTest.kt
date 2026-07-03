package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.utility.DockerImageName
import java.awt.Rectangle
import java.io.ByteArrayInputStream
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
        val actual = BufferedImage(1280, 900, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(10, 20, 1260, 860)
            graphics.dispose()
        }

        val metrics = intellijVisualRegionMetrics(
            text = text,
            expected = visualCapture(expected),
            actualRobot = visualCapture(actual),
            actualSvg = visualCapture(actual),
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
    fun `intellij log artifact names preserve pid suffixes`() {
        assertEquals("intellij-kotlin-jcef.log", intellijLogArtifactName("intellij-kotlin", "/tmp/idea-log/jcef.log"))
        assertEquals("intellij-kotlin-jcef-55.log", intellijLogArtifactName("intellij-kotlin", "/tmp/idea-log/jcef_55.log"))
        assertEquals(
            "intellij-kotlin-jcef-chromium-55.log",
            intellijLogArtifactName("intellij-kotlin", "/tmp/idea-log/jcef_chromium_55.log"),
        )
    }

    @Test
    fun `intellij glx jcef diagnostics summary extracts preflight and angle failures`() {
        val kotlinText =
            """
            - #8 QueryServerString minor=19 screen=0 name=3 value=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile
            - #7 SetClientInfo2ARB minor=35 layout=spec client=1.4 versions=1 glBytes=14 glxBytes=67 glExtensions=GL_EXT_texture glxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile
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
                        GLX_EXT_create_context_es_profile
                    GLX visuals:
                    """.trimIndent(),
            ),
            IntellijLogArtifact(
                fileName = "intellij-kotlin-run.log",
                text = "ANGLE Display::initialize error 12289: Could not create the initialization pbuffer.",
            ),
        )
        val summary = intellijGlxJcefDiagnosticsSummary(
            logs = logs + IntellijLogArtifact(fileName = "intellij-kotlin-text.txt", text = kotlinText),
        )
        val summaryFromExplicitText = intellijGlxJcefDiagnosticsSummary(logs, kotlinText = kotlinText)

        assertTrue(summary.contains("xvfbGlxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile"), summary)
        assertTrue(
            summary.contains("kotlinGlxExtensions=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(summary.contains("kotlinListsGlxExtension=true"), summary)
        assertTrue(summary.contains("kotlinXdpyinfoGlxDetailUnsupported=true"), summary)
        assertTrue(
            summary.contains("kotlinClientGlxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(
            summary.contains("kotlinServerGlxExtensionsFromTrace=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(
            summaryFromExplicitText.contains("kotlinClientGlxExtensions=GLX_ARB_create_context GLX_EXT_create_context_es_profile"),
            summaryFromExplicitText,
        )
        assertTrue(
            summaryFromExplicitText.contains("kotlinServerGlxExtensionsFromTrace=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile"),
            summaryFromExplicitText,
        )
        assertTrue(summary.contains("kotlinExplicitTextTraceIncluded=false"), summary)
        assertTrue(summaryFromExplicitText.contains("kotlinExplicitTextTraceIncluded=true"), summaryFromExplicitText)
        assertTrue(summary.contains("kotlinAngleInitializationPbufferFailure=true"), summary)
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
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withFileSystemBind(cleanProjectExport().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
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
                        IDEA_X11_DEBUG=${intellijDebugValue()} \
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
                    dumpIntellijLogArtifacts(
                        collectIntellijLogs(container, "intellij-smoke", "/tmp/idea-run-smoke.log"),
                    )
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

        val composedSvg = composeSvgLayers(actual.svgLayers, IntellijCaptureWidth, IntellijCaptureHeight)
        val composedSvgCapture = visualCapture(composedSvg)
        dumpIntellijParityArtifacts(
            reference = reference,
            actual = actual,
            composedSvg = composedSvg,
            composedSvgCapture = composedSvgCapture,
        )
        assertIntellijVisualClose(reference.robot, actual.robot, "Kotlin Robot IntelliJ capture")
        assertIntellijVisualClose(reference.robot, composedSvgCapture, "Kotlin SVG-composed IntelliJ framebuffer")
    }

    private fun projectRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

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

    private fun intellijDebugEnabled(): Boolean =
        System.getProperty("x.intellijDebug") == "true" || System.getenv("X_INTELLIJ_DEBUG") == "true"

    private fun intellijDebugValue(): String =
        if (intellijDebugEnabled()) "true" else "false"

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

    private fun runIntellijAgainstXvfb(image: String, url: String?): IntellijReferenceCapture =
        GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
            .withFileSystemBind(cleanProjectExport().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
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
                    DISPLAY=:99 xdpyinfo -ext GLX >/tmp/xdpyinfo-glx-xvfb.log 2>&1 || true
                    DISPLAY=:99 xsetroot -solid white >/tmp/xsetroot.log 2>&1 || true
                    DISPLAY=:99 \
                    IDEA_X11_DEBUG=${intellijDebugValue()} \
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
                val logs = collectIntellijLogs(
                    container = container,
                    prefix = "intellij-xvfb",
                    runLogPath = "/tmp/idea-run-xvfb.log",
                    extraLogs = listOf("/tmp/xdpyinfo-glx-xvfb.log" to "intellij-xvfb-glx-xdpyinfo.log"),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                IntellijReferenceCapture(
                    robot = visualCapture(result.stdout),
                    logs = logs,
                )
            }

    private fun runIntellijAgainstKotlinServer(port: Int, image: String, url: String?): IntellijKotlinCapture {
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
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withFileSystemBind(cleanProjectExport().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
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
                        DISPLAY=host.docker.internal:$display xdpyinfo -ext GLX >/tmp/xdpyinfo-glx-kotlin.log 2>&1 || true
                        DISPLAY=host.docker.internal:$display \
                        IDEA_X11_DEBUG=${intellijDebugValue()} \
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
                        val logs = collectIntellijLogs(
                            container = container,
                            prefix = "intellij-kotlin",
                            runLogPath = "/tmp/idea-run-parity.log",
                            extraLogs = listOf("/tmp/xdpyinfo-glx-kotlin.log" to "intellij-kotlin-glx-xdpyinfo.log"),
                        )
                        return IntellijKotlinCapture(
                            robot = visualCapture(capture.stdout),
                            text = snapshot.text,
                            svg = svg,
                            svgLayers = svgCompositionLayers(svg),
                            logs = logs,
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
        return Regex("""<(?:image|rect)\b[^>]*>""")
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
        File(directory, "intellij-kotlin-text.txt").writeText(actual.text)
        File(directory, "intellij-kotlin-svg-layers.txt").writeText(svgLayerInventory(actual.svgLayers))
        val logs = reference.logs + actual.logs
        dumpIntellijLogArtifacts(logs)
        File(directory, "intellij-glx-jcef-diagnostics.txt").writeText(intellijGlxJcefDiagnosticsSummary(logs, kotlinText = actual.text))
        dumpIntellijVisualDiff(directory, "intellij-kotlin-robot-vs-xvfb", reference.robot, actual.robot)
        dumpIntellijVisualDiff(directory, "intellij-kotlin-svg-vs-xvfb", reference.robot, composedSvgCapture)
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
        val dynamicLogs = container.execInContainer(
            "sh",
            "-lc",
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
            val result = container.execInContainer(
                "sh",
                "-lc",
                "if [ -f '$path' ]; then cat '$path'; fi",
            )
            if (result.exitCode == 0 && result.stdout.isNotEmpty()) {
                IntellijLogArtifact(fileName = fileName, text = result.stdout)
            } else {
                null
            }
        }
    }

    private fun intellijGlxJcefDiagnosticsSummary(logs: List<IntellijLogArtifact>, kotlinText: String? = null): String {
        val xvfbGlx = logs.firstOrNull { it.fileName == "intellij-xvfb-glx-xdpyinfo.log" }?.text.orEmpty()
        val kotlinGlx = logs.firstOrNull { it.fileName == "intellij-kotlin-glx-xdpyinfo.log" }?.text.orEmpty()
        val kotlinTraceArtifacts = logs.filter { it.fileName.startsWith("intellij-kotlin-") }
        val kotlinTrace = (
            kotlinTraceArtifacts.map { it.text } +
                listOfNotNull(kotlinText)
            ).joinToString("\n")
        return buildString {
            appendLine("kotlinExplicitTextTraceIncluded=${!kotlinText.isNullOrBlank()}")
            appendLine("kotlinTraceArtifacts=${kotlinTraceArtifacts.joinToString(" ") { it.fileName }}")
            appendLine("xvfbListsGlxExtension=${listedExtensionsFromXdpyinfo(xvfbGlx).contains("GLX")}")
            appendLine("kotlinListsGlxExtension=${listedExtensionsFromXdpyinfo(kotlinGlx).contains("GLX")}")
            appendLine("xvfbXdpyinfoGlxDetailUnsupported=${xdpyinfoGlxDetailUnsupported(xvfbGlx)}")
            appendLine("kotlinXdpyinfoGlxDetailUnsupported=${xdpyinfoGlxDetailUnsupported(kotlinGlx)}")
            appendLine("xvfbGlxExtensions=${glxExtensionsFromXdpyinfo(xvfbGlx).joinToString(" ")}")
            appendLine("kotlinGlxExtensions=${glxExtensionsFromXdpyinfo(kotlinGlx).joinToString(" ")}")
            appendLine("kotlinServerGlxExtensionsFromTrace=${serverGlxExtensionsFromText(kotlinTrace).joinToString(" ")}")
            appendLine("kotlinClientGlxExtensions=${clientGlxExtensionsFromText(kotlinTrace).joinToString(" ")}")
            appendLine("kotlinAngleInitializationPbufferFailure=${kotlinTrace.contains("Could not create the initialization pbuffer")}")
            appendLine(
                "kotlinAngleMissingEsProfileMessage=${
                    kotlinTrace.contains("Cannot create an OpenGL ES platform on GLX without the GLX_EXT_create_context_es_profile extension")
                }",
            )
        }
    }

    private fun listedExtensionsFromXdpyinfo(text: String): List<String> {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim().startsWith("number of extensions:") }
        if (start < 0) return emptyList()
        return lines
            .asSequence()
            .drop(start + 1)
            .takeWhile { line -> line.isBlank() || line.firstOrNull()?.isWhitespace() == true }
            .map { it.trim() }
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
        }
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

    private data class IntellijReferenceCapture(
        val robot: VisualCapture,
        val logs: List<IntellijLogArtifact>,
    )

    private data class IntellijKotlinCapture(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val svgLayers: List<SvgLayer>,
        val logs: List<IntellijLogArtifact>,
    )

    private data class IntellijLogArtifact(
        val fileName: String,
        val text: String,
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
