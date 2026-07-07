package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VSCodeSmokeTest {
    private companion object {
        const val VSCodeCaptureWidth = 1280
        const val VSCodeCaptureHeight = 900
        const val VSCodeContainerCommandTimeoutSeconds = 180
    }

    @Test
    fun `vscode diagnostics summary extracts extension and glx evidence`() {
        val text = """
            Window hierarchy and geometry:
            - 0x200003 parent=0x26 label="code" geometry=0,0 1279x899 class=InputOutput depth=24 visual=0x21 backgroundPixel=-592396 backgroundPixmap=none borderPixel=0 borderPixmap=none bitGravity=1 winGravity=1 backingStore=0 backingPlanes=-1 backingPixel=0 saveUnder=false overrideRedirect=false colormap=0x27 cursor=0x20000d mapped=true focused=true stack=4

            Extension queries:
            - #57 XInputExtension supported=true
            - #56 XFIXES supported=true
            - #55 SYNC supported=true
            - #51 RENDER supported=true
            - #49 GLX supported=true
            - #48 DRI3 supported=false

            Unsupported requests:
            - None.

            GLX operations:
            - #8 QueryServerString minor=19 screen=0 name=3 value=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es_profile GLX_EXT_create_context_es2_profile
            - #7 SetClientInfo2ARB minor=35 layout=spec client=1.4 versions=17 glBytes=1 glxBytes=54 glExtensions= glxExtensions=GLX_ARB_create_context GLX_ARB_create_context_profile
            - #6 GetFBConfigs minor=21 screen=0
            - #1 QueryVersion minor=7 client=1.4
        """.trimIndent()
        val summary = vscodeDiagnosticsSummary(
            text = text,
            logs = listOf(
                VSCodeLogArtifact(
                    fileName = "vscode-kotlin-vscode-run.log",
                    text = "[16:0703/182742.562139:ERROR:dbus/bus.cc:405] Failed to connect to the bus",
                ),
            ),
        )

        assertTrue(summary.contains("vscodeWindowEvidence=true"), summary)
        assertTrue(summary.contains("vscodeUnsupportedRequests=None"), summary)
        assertTrue(summary.contains("vscodeUnsupportedExtensions=DRI3"), summary)
        assertTrue(summary.contains("vscodeSupportedExtensions=GLX RENDER SYNC XFIXES XInputExtension"), summary)
        assertTrue(summary.contains("vscodeGlxOperations=GetFBConfigs QueryServerString QueryVersion SetClientInfo2ARB"), summary)
        assertTrue(
            summary.contains("vscodeServerGlxExtensions=GLX_ARB_create_context GLX_ARB_create_context_profile GLX_EXT_create_context_es2_profile GLX_EXT_create_context_es_profile"),
            summary,
        )
        assertTrue(summary.contains("vscodeClientGlxExtensions=GLX_ARB_create_context GLX_ARB_create_context_profile"), summary)
        assertTrue(summary.contains("vscodeDbusLogWarnings=true"), summary)
        assertFalse(summary.contains("vscodeUnsupportedExtensions=XInputExtension DRI3"), summary)
    }

    @Test
    fun `vscode unsupported request gate rejects protocol gaps`() {
        assertNoVSCodeUnsupportedRequests(
            """
                Unsupported requests:
                - None.
            """.trimIndent(),
            label = "fixture",
        )

        val failure = assertFailsWith<AssertionError> {
            assertNoVSCodeUnsupportedRequests(
                """
                    Unsupported requests:
                    - #12 RENDER.MissingGlyphPath opcode=139 minor=99
                """.trimIndent(),
                label = "fixture",
            )
        }
        assertTrue(failure.message?.contains("RENDER.MissingGlyphPath") == true, failure.message)
    }

    @Test
    fun `vscode parity artifact directory is reset before a new bundle`() {
        val directory = vscodeSmokeArtifactsDirectory()
        val stale = File(directory, "vscode-xvfb-reference.png")
        val retainedInputDirectory = File(directory, "project").also { it.mkdirs() }
        val retainedInput = File(retainedInputDirectory, "README.md")
        stale.writeText("stale image")
        retainedInput.writeText("tracked input")

        val prepared = prepareVSCodeParityArtifactsDirectory()

        assertEquals(directory, prepared)
        assertTrue(prepared.isDirectory)
        assertFalse(stale.exists(), "stale VSCode parity artifacts must not survive into the next bundle")
        assertTrue(retainedInput.isFile, "generated VSCode project input must survive artifact cleanup")
    }

    @Test
    fun `vscode svg composition skips hidden image layers`() {
        val visible = onePixelPngBase64(0xff12_3456.toInt())
        val hidden = onePixelPngBase64(0xffff_0000.toInt())
        val svg = """
            <svg width="2" height="1" xmlns="http://www.w3.org/2000/svg">
              <g visibility="hidden">
                <image data-window-id="0xhidden" x="0" y="0" width="1" height="1" href="data:image/png;base64,$hidden"/>
              </g>
              <image data-window-id="0xvisible" x="1" y="0" width="1" height="1" href="data:image/png;base64,$visible"/>
            </svg>
        """.trimIndent()

        val layers = svgCompositionLayers(svg)
        val image = composePngLayers(layers, width = 2, height = 1)

        assertEquals(listOf("0xvisible"), layers.map { it.id })
        assertEquals(0xff00_0000.toInt(), image.getRGB(0, 0), "Hidden image should not be composed over the black background")
        assertEquals(0xff12_3456.toInt(), image.getRGB(1, 0), "Visible image should be composed")
    }

    @Test
    fun `vscode svg composition parser prefers composited root framebuffer`() {
        val root = onePixelPngBase64(0xff12_3456.toInt())
        val hidden = onePixelPngBase64(0xffff_0000.toInt())
        val svg = """
            <svg width="2" height="1" xmlns="http://www.w3.org/2000/svg">
              <image class="framebuffer-image screen-framebuffer-image" data-source="composited-root" data-window-id="0x26" x="0" y="0" width="2" height="1" href="data:image/png;base64,$root"/>
              <g class="semantic-window-layers" visibility="hidden">
                <image class="framebuffer-image" data-source="window-framebuffer" data-window-id="0xhidden" x="0" y="0" width="1" height="1" href="data:image/png;base64,$hidden"/>
              </g>
            </svg>
        """.trimIndent()

        val layers = svgCompositionLayers(svg)
        val image = composePngLayers(layers, width = 2, height = 1)

        assertEquals(listOf("0x26"), layers.map { it.id })
        assertEquals(listOf("composited-root"), layers.map { it.source })
        assertEquals(0xff12_3456.toInt(), image.getRGB(0, 0))
        assertEquals(0xff12_3456.toInt(), image.getRGB(1, 0))
    }

    @Test
    fun `vscode html preview parser identifies large code window surfaces`() {
        val html = """
            <section class="window-contents">
              <article class="preview">
                <header><strong>0x200003</strong> <span>0x200003</span> 1279x899 focused</header>
                <svg class="window-preview-svg" viewBox="0 0 1279 899" aria-label="0x200003">
                  <image class="framebuffer-image" data-window-id="0x200003" data-source="window-framebuffer" x="0" y="0" width="1279" height="899" href="data:image/png;base64,aGVsbG8="/>
                </svg>
              </article>
            </section>
        """.trimIndent()
        val text = """
            Window hierarchy and geometry:
            - 0x26 parent=0x0 label="root" geometry=0,0 1280x900 class=InputOutput mapped=true
            - 0x200003 parent=0x26 label="0x200003" geometry=0,0 1279x899 class=InputOutput mapped=true focused=true
        """.trimIndent()

        val previews = htmlWindowPreviewSurfaces(html)

        assertEquals(1, previews.size)
        assertEquals("0x200003", previews.single().label)
        assertEquals(1279, previews.single().viewWidth)
        assertEquals(899, previews.single().viewHeight)
        assertEquals("window-framebuffer", previews.single().source)
        assertEquals("0x200003", previews.single().windowId)
        assertVSCodeHtmlPreviewHasLargeSurface(html)
        assertVSCodeHtmlPreviewHasLargeSurface(html, text)
        assertFailsWith<AssertionError> {
            assertVSCodeHtmlPreviewHasLargeSurface(html, "Window hierarchy and geometry:\n- no mapped root child")
        }
    }

    @Test
    fun `vscode visual region metrics identify code window and mismatch bounds`() {
        val text = """
            Window hierarchy and geometry:
            - 0x26 parent=none label="root" geometry=0,0 20x10 class=InputOutput depth=24 visual=0x21 backgroundPixel=0 backgroundPixmap=none borderPixel=0 borderPixmap=none mapped=true focused=false stack=0
            - 0x200003 parent=0x26 label="code" geometry=2,1 10x6 class=InputOutput depth=24 visual=0x21 backgroundPixel=0 backgroundPixmap=none borderPixel=0 borderPixmap=none mapped=true focused=true stack=1
        """.trimIndent()
        val expected = solidImage(20, 10, 0xffff_ffff.toInt()).also {
            fillRect(it, Rectangle(2, 1, 10, 6), 0xff12_3456.toInt())
        }
        val actualRobot = solidImage(20, 10, 0xffff_ffff.toInt()).also {
            fillRect(it, Rectangle(2, 1, 10, 6), 0xff12_3456.toInt())
            fillRect(it, Rectangle(5, 3, 2, 1), 0xffff_ffff.toInt())
        }
        val actualSvg = solidImage(20, 10, 0xffff_ffff.toInt()).also {
            fillRect(it, Rectangle(2, 1, 10, 6), 0xff12_3456.toInt())
        }

        val metrics = vscodeVisualRegionMetrics(
            text = text,
            expected = visualCapture(expected),
            actualRobot = visualCapture(actualRobot),
            actualSvg = visualCapture(actualSvg),
        )

        assertTrue(metrics.contains("vscodeWindow=2,1 10x6"), metrics)
        assertTrue(metrics.contains("leftMargin=2"), metrics)
        assertTrue(metrics.contains("robotInsideWindowCoverageRatio=0.9666666666666667"), metrics)
        assertTrue(metrics.contains("robotMismatchBounds=5,3 2x1"), metrics)
        assertTrue(metrics.contains("svgMismatchBounds=none"), metrics)
    }

    @Test
    fun `vscode from official tarball starts against kotlin x server`() {
        assumeTrue(
            System.getProperty("x.vscodeSmoke") == "true" || System.getenv("X_VSCODE_SMOKE") == "true",
            "Set -Dx.vscodeSmoke=true or X_VSCODE_SMOKE=true to download and run the heavyweight VSCode smoke",
        )
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

        val port = 6232
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        val url = System.getProperty("x.vscodeUrl") ?: System.getenv("X_VSCODE_URL")
        val image = System.getProperty("x.vscodeImage")
            ?: System.getenv("X_VSCODE_IMAGE")
            ?: "jonnyzzz-x/x11-client:latest"
        assumeTrue(imageExists(image), "Build $image first with scripts/run-supervised.sh gradle dockerBuildX11Client")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 1280, height = 900)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            vscodeContainer(image)
                .use { container ->
                    container.start()
                    val display = port - 6000
                    val startResult = execVSCodeShell(
                        container,
                        """
                        set -eu
                        command -v run-vscode
                        if [ -n "${url.orEmpty()}" ]; then
                          export VSCODE_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display \
                        VSCODE_PROJECT=/workspace/jonnyzzz-x \
                        run-vscode >/tmp/vscode-run.log 2>&1 &
                        echo "${'$'}!" >/tmp/vscode.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)
                    try {
                        val snapshot = try {
                            waitForVisibleVSCodePixels(port)
                        } catch (t: Throwable) {
                            dumpVSCodeArtifactsBestEffort(container, port, t)
                            throw t
                        }
                        val text = httpGet(port, "/text.txt")
                        val svg = httpGet(port, "/screen.svg")
                        val html = httpGet(port, "/")
                        dumpVSCodeArtifacts(text = text, svg = svg, html = html, logs = collectVSCodeLogs(container))
                        val running = execContainerShell(container, 30, "kill -0 $(cat /tmp/vscode.pid)")
                        assertEquals(0, running.exitCode, "VSCode exited early\n${vscodeRunLog(container)}")
                        assertTrue(
                            hasVSCodeWindowEvidence(text),
                            "VSCode smoke should expose a labeled editor window in the HTTP report\n$text",
                        )
                        assertNoVSCodeUnsupportedRequests(text, label = "VSCode smoke")
                        assertVSCodeHtmlPreviewHasLargeSurface(html, text)
                        assertTrue(
                            snapshot.stats.any { it.hasVisibleContent() },
                            "VSCode screen SVG should contain non-white rendered pixels, got ${snapshot.stats}\n$text",
                        )
                    } finally {
                        execContainerShell(
                            container,
                            30,
                            "kill $(cat /tmp/vscode.pid 2>/dev/null || pgrep -f '/opt/vscode/code') 2>/dev/null || true",
                        )
                    }
                }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `vscode robot and svg roughly match xvfb reference`() {
        assumeTrue(
            System.getProperty("x.vscodeParity") == "true" || System.getenv("X_VSCODE_PARITY") == "true",
            "Set -Dx.vscodeParity=true or X_VSCODE_PARITY=true to run the heavyweight VSCode Xvfb parity probe",
        )
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

        val port = 6232
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        val url = System.getProperty("x.vscodeUrl") ?: System.getenv("X_VSCODE_URL")
        val clientImage = System.getProperty("x.vscodeImage")
            ?: System.getenv("X_VSCODE_IMAGE")
            ?: "jonnyzzz-x/x11-client:latest"
        val referenceImage = System.getProperty("x.vscodeReferenceImage")
            ?: System.getenv("X_VSCODE_REFERENCE_IMAGE")
            ?: "jonnyzzz-x/x11-reference:latest"
        assumeTrue(imageExists(clientImage), "Build $clientImage first with scripts/run-supervised.sh gradle dockerBuildX11Client")
        assumeTrue(imageExists(referenceImage), "Build $referenceImage first with scripts/run-supervised.sh gradle dockerBuildX11Images")

        val reference = runVSCodeAgainstXvfb(referenceImage, url)
        assertTrue(
            reference.robot.nonWhitePixels > 10_000,
            "VSCode Xvfb reference capture should contain visible painted content; reference=${reference.robot}",
        )
        val actual = runVSCodeAgainstKotlinServer(port, clientImage, url)
        val composedSvg = composePngLayers(actual.svgLayers, VSCodeCaptureWidth, VSCodeCaptureHeight)
        val composedSvgCapture = visualCapture(composedSvg)

        dumpVSCodeParityArtifacts(reference, actual, composedSvg, composedSvgCapture)
        assertVSCodeHtmlPreviewHasLargeSurface(actual.html, actual.text)
        assertNoVSCodeUnsupportedRequests(actual.text, label = "VSCode parity")
        assertVSCodeVisualClose(reference.robot, actual.robot, "Kotlin Robot VSCode capture")
        assertVSCodeVisualClose(reference.robot, composedSvgCapture, "Kotlin SVG-composed VSCode framebuffer")
    }

    private fun projectRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

    private fun cleanProjectExport(): Path {
        val root = projectRoot()
        val export = root.resolve("build/tmp/vscode-smoke/project").normalize()
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

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun imageExists(image: String): Boolean =
        runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess

    private fun vscodeContainer(image: String): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
            .withFileSystemBind(cleanProjectExport().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
            .withFileSystemBind(
                projectRoot().resolve("docker/x11-client/run-vscode.sh").toString(),
                "/usr/local/bin/run-vscode",
                BindMode.READ_ONLY,
            )
            .withCommand("sleep", "900")

    private fun execContainerShell(container: GenericContainer<*>, timeoutSeconds: Int, script: String) =
        container.execInContainer("timeout", "${timeoutSeconds}s", "sh", "-lc", script)

    private fun execVSCodeShell(container: GenericContainer<*>, script: String) =
        execContainerShell(container, VSCodeContainerCommandTimeoutSeconds, script)

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun waitForVisibleVSCodePixels(port: Int): VSCodeVisualSnapshot {
        val deadline = System.currentTimeMillis() + 90_000
        var lastFailure: Throwable? = null
        var lastSnapshot = VSCodeVisualSnapshot(emptyList(), "")
        while (System.currentTimeMillis() < deadline) {
            try {
                val svg = httpGet(port, "/screen.svg")
                val text = httpGet(port, "/text.txt")
                val stats = pngDataUris(svg).map { imageStats(it.id, it.bytes) }
                val snapshot = VSCodeVisualSnapshot(stats, text)
                lastSnapshot = snapshot
                if (
                    hasVSCodeWindowEvidence(text) &&
                    stats.any { it.hasVisibleContent() }
                ) {
                    return snapshot
                }
            } catch (t: Throwable) {
                lastFailure = t
            }
            Thread.sleep(250)
        }
        val failureMessage = lastFailure?.let { "\nLast polling failure: ${it::class.qualifiedName}: ${it.message}" }.orEmpty()
        assertTrue(
            lastSnapshot.stats.any { it.hasVisibleContent() },
            "VSCode screen SVG did not expose non-white rendered pixels before timeout, got ${lastSnapshot.stats}\n${lastSnapshot.text}$failureMessage",
        )
        return lastSnapshot
    }

    private fun runVSCodeAgainstXvfb(image: String, url: String?): VSCodeReferenceCapture =
        vscodeContainer(image)
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = execVSCodeShell(
                    container,
                    """
                    set -eu
                    command -v Xvfb
                    command -v run-vscode
                    if [ -n "${url.orEmpty()}" ]; then
                      export VSCODE_URL="${url.orEmpty()}"
                    fi
                    Xvfb :99 -screen 0 ${VSCodeCaptureWidth}x${VSCodeCaptureHeight}x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}code" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 80); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 \
                    VSCODE_PROJECT=/workspace/jonnyzzz-x \
                    run-vscode >/tmp/vscode-run.log 2>&1 &
                    code=${'$'}!
                    opened=0
                    for _ in ${'$'}(seq 1 90); do
                      if DISPLAY=:99 xwininfo -root -tree 2>/dev/null | grep -Eiq 'Visual Studio Code|code'; then
                        opened=1
                        break
                      fi
                      if ! kill -0 "${'$'}code" 2>/dev/null; then
                        break
                      fi
                      sleep 1
                    done
                    if [ "${'$'}opened" -ne 1 ]; then
                      cat /tmp/vscode-run.log 2>/dev/null || true
                      DISPLAY=:99 xwininfo -root -tree 2>/dev/null || true
                      exit 1
                    fi
                    sleep 5
                    DISPLAY=:99 java -cp /tmp XVSCodeRobotCapture
                    """.trimIndent(),
                )
                val logs = collectVSCodeLogs(container, prefix = "vscode-xvfb")
                if (result.exitCode != 0) {
                    val directory = vscodeSmokeArtifactsDirectory()
                    logs.forEach { artifact -> File(directory, artifact.fileName).writeText(artifact.text) }
                    File(directory, "vscode-xvfb-reference-failure.log").writeText(result.stderr + result.stdout)
                }
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                VSCodeReferenceCapture(
                    robot = visualCapture(result.stdout),
                    logs = logs,
                )
            }

    private fun runVSCodeAgainstKotlinServer(port: Int, image: String, url: String?): VSCodeKotlinCapture {
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = VSCodeCaptureWidth,
                height = VSCodeCaptureHeight,
                rootBackgroundPixel = 0x0000_0000,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            vscodeContainer(image)
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = execVSCodeShell(
                        container,
                        """
                        set -eu
                        command -v run-vscode
                        if [ -n "${url.orEmpty()}" ]; then
                          export VSCODE_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display \
                        VSCODE_PROJECT=/workspace/jonnyzzz-x \
                        run-vscode >/tmp/vscode-run.log 2>&1 &
                        pid=${'$'}!
                        echo "${'$'}pid" >/tmp/vscode.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)
                    try {
                        try {
                            val snapshot = waitForVisibleVSCodePixels(port)
                            Thread.sleep(3_000)
                            val capture = execContainerShell(
                                container,
                                60,
                                """
                                set -eu
                                pid=${'$'}(cat /tmp/vscode.pid)
                                kill -0 "${'$'}pid"
                                DISPLAY=host.docker.internal:$display java -cp /tmp XVSCodeRobotCapture
                                """.trimIndent(),
                            )
                            assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                            val svg = httpGet(port, "/screen.svg")
                            val html = httpGet(port, "/")
                            val text = httpGet(port, "/text.txt")
                            val logs = collectVSCodeLogs(container, prefix = "vscode-kotlin")
                            return VSCodeKotlinCapture(
                                robot = visualCapture(capture.stdout),
                                text = text,
                                svg = svg,
                                html = html,
                                svgLayers = svgCompositionLayers(svg),
                                logs = logs,
                            )
                        } catch (t: Throwable) {
                            dumpVSCodeArtifactsBestEffort(container, port, t)
                            throw t
                        }
                    } finally {
                        execContainerShell(
                            container,
                            30,
                            "kill $(cat /tmp/vscode.pid 2>/dev/null || pgrep -f '/opt/vscode/code') 2>/dev/null || true",
                        )
                        server.close()
                        serverThread.join(1_000)
                    }
                }
        }
    }

    private fun hasVSCodeWindowEvidence(text: String): Boolean =
        text.contains("Visual Studio Code") ||
            text.contains("label=\"code\"") ||
            text.contains("Chromium clipboard")

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                if (isInsideHiddenSvgGroup(svg, match.range.first)) return@mapNotNull null
                val tag = match.value
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                EmbeddedPng(
                    id = id,
                    bytes = Base64.getDecoder().decode(encoded),
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

    private fun assertVSCodeHtmlPreviewHasLargeSurface(html: String, text: String = "") {
        assertTrue(html.contains("""class="window-contents""""), "VSCode HTML capture must include the window preview section")
        val codeWindowId = largestMappedRootChildWindowId(text)
        val previews = htmlWindowPreviewSurfaces(html)
        if (text.isNotBlank()) {
            assertTrue(
                codeWindowId != null,
                "VSCode text capture must expose a mapped root-child window id for HTML preview correlation\n$text",
            )
        }
        val largeCodePreviews = previews.filter {
            (if (codeWindowId == null) it.label.contains("code", ignoreCase = true) || it.label.matches(Regex("""0x[0-9a-fA-F]+""")) else it.windowId == codeWindowId) &&
                it.viewWidth >= 640 &&
                it.viewHeight >= 360 &&
                it.width >= 640 &&
                it.height >= 360 &&
                it.source in setOf("window-framebuffer", "matching-pixmap", "retained-picture")
        }
        assertTrue(
            largeCodePreviews.isNotEmpty(),
            "VSCode HTML capture must expose a large Code window framebuffer/backing surface, codeWindowId=$codeWindowId previews=$previews",
        )
    }

    private fun imageStats(id: String, bytes: ByteArray): VSCodeImageStats {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return VSCodeImageStats(id, 0, 0, 0, 0, emptyList())
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
        return VSCodeImageStats(
            id = id,
            width = image.width,
            height = image.height,
            nonWhitePixels = count,
            distinctNonWhiteColors = colors.size,
            samples = samples.map { "0x${it.toUInt().toString(16)}" },
        )
    }

    private fun onePixelPngBase64(argb: Int): String {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, argb)
        val output = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun solidImage(width: Int, height: Int, argb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
            fillRect(it, Rectangle(0, 0, width, height), argb)
        }

    private fun fillRect(image: BufferedImage, rectangle: Rectangle, argb: Int) {
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(argb, true)
            graphics.fillRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height)
        } finally {
            graphics.dispose()
        }
    }

    private fun compileRobotCapture(container: GenericContainer<*>) {
        val result = execContainerShell(
            container,
            60,
            "cat > /tmp/XVSCodeRobotCapture.java <<'JAVA'\n${robotCaptureSource()}\nJAVA\njavac /tmp/XVSCodeRobotCapture.java",
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

        public class XVSCodeRobotCapture {
          public static void main(String[] args) throws Exception {
            Robot robot = new Robot();
            Thread.sleep(1200);
            BufferedImage image = robot.createScreenCapture(
                new Rectangle(0, 0, $VSCodeCaptureWidth, $VSCodeCaptureHeight));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
          }
        }
        """.trimIndent()

    private fun composePngLayers(layers: List<SvgLayer>, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.BLACK
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

    private fun dumpVSCodeArtifacts(text: String, svg: String, html: String? = null, logs: List<VSCodeLogArtifact>) {
        val directory = vscodeSmokeArtifactsDirectory()
        File(directory, "vscode-kotlin-text.txt").writeText(text)
        File(directory, "vscode-kotlin-screen.svg").writeText(svg)
        html?.let {
            File(directory, "vscode-kotlin.html").writeText(it)
            File(directory, "vscode-kotlin-html-previews.txt").writeText(htmlPreviewInventory(htmlWindowPreviewSurfaces(it)))
        }
        File(directory, "vscode-diagnostics.txt").writeText(vscodeDiagnosticsSummary(text, logs))
        logs.forEach { artifact -> File(directory, artifact.fileName).writeText(artifact.text) }
    }

    private fun dumpVSCodeParityArtifacts(
        reference: VSCodeReferenceCapture,
        actual: VSCodeKotlinCapture,
        composedSvg: BufferedImage,
        composedSvgCapture: VisualCapture,
    ) {
        val directory = prepareVSCodeParityArtifactsDirectory()
        ImageIO.write(reference.robot.image, "png", File(directory, "vscode-xvfb-reference.png"))
        ImageIO.write(actual.robot.image, "png", File(directory, "vscode-kotlin-robot.png"))
        ImageIO.write(composedSvg, "png", File(directory, "vscode-kotlin-svg-composed.png"))
        File(directory, "vscode-kotlin-screen.svg").writeText(actual.svg)
        File(directory, "vscode-kotlin.html").writeText(actual.html)
        File(directory, "vscode-kotlin-text.txt").writeText(actual.text)
        File(directory, "vscode-kotlin-svg-layers.txt").writeText(svgLayerInventory(actual.svgLayers))
        File(directory, "vscode-kotlin-html-previews.txt").writeText(htmlPreviewInventory(htmlWindowPreviewSurfaces(actual.html)))
        val logs = reference.logs + actual.logs
        logs.forEach { artifact -> File(directory, artifact.fileName).writeText(artifact.text) }
        File(directory, "vscode-diagnostics.txt").writeText(vscodeDiagnosticsSummary(actual.text, logs))
        dumpVSCodeVisualDiff(directory, "vscode-kotlin-robot-vs-xvfb", reference.robot, actual.robot)
        dumpVSCodeVisualDiff(directory, "vscode-kotlin-svg-vs-xvfb", reference.robot, composedSvgCapture)
        File(directory, "vscode-visual-region-metrics.txt").writeText(
            vscodeVisualRegionMetrics(
                text = actual.text,
                expected = reference.robot,
                actualRobot = actual.robot,
                actualSvg = composedSvgCapture,
            ),
        )
        File(directory, "vscode-visual-metrics.txt").writeText(
            buildString {
                appendLine("screen=${reference.robot.width}x${reference.robot.height}")
                appendLine("expectedFullScreen=${reference.robot}")
                appendLine("robotFullScreen=${actual.robot}")
                appendLine("svgFullScreen=$composedSvgCapture")
                appendLine("robotCoverageRatio=${ratio(actual.robot.nonWhitePixels, reference.robot.nonWhitePixels)}")
                appendLine("svgCoverageRatio=${ratio(composedSvgCapture.nonWhitePixels, reference.robot.nonWhitePixels)}")
                appendLine("robotAverageRgbDelta=${abs(actual.robot.averageRgb - reference.robot.averageRgb)}")
                appendLine("svgAverageRgbDelta=${abs(composedSvgCapture.averageRgb - reference.robot.averageRgb)}")
                appendLine("robotSampledDistance=${imageDistance(reference.robot.image, actual.robot.image)}")
                appendLine("svgSampledDistance=${imageDistance(reference.robot.image, composedSvgCapture.image)}")
            },
        )
    }

    private fun dumpVSCodeArtifactsBestEffort(container: GenericContainer<*>, port: Int, failure: Throwable) {
        val text = runCatching { httpGet(port, "/text.txt") }
            .getOrElse { "Failed to fetch /text.txt: ${it::class.qualifiedName}: ${it.message}" }
        val svg = runCatching { httpGet(port, "/screen.svg") }
            .getOrElse { "<!-- Failed to fetch /screen.svg: ${it::class.qualifiedName}: ${it.message} -->" }
        val html = runCatching { httpGet(port, "/") }.getOrNull()
        val logs = runCatching { collectVSCodeLogs(container) }
            .getOrElse {
                listOf(
                    VSCodeLogArtifact(
                        fileName = "vscode-kotlin-artifact-collection-failure.log",
                        text = "${it::class.qualifiedName}: ${it.message}",
                    ),
                )
            } + VSCodeLogArtifact(
                fileName = "vscode-kotlin-smoke-failure.log",
                text = "${failure::class.qualifiedName}: ${failure.message}",
            )
        dumpVSCodeArtifacts(text = text, svg = svg, html = html, logs = logs)
    }

    private fun vscodeSmokeArtifactsDirectory(): File =
        projectRoot().resolve("build/tmp/vscode-smoke").toFile().also { it.mkdirs() }

    private fun prepareVSCodeParityArtifactsDirectory(): File =
        projectRoot().resolve("build/tmp/vscode-smoke").toFile().also {
            it.mkdirs()
            it.listFiles()?.filter { child -> child.isFile }?.forEach { child -> child.delete() }
        }

    private fun collectVSCodeLogs(container: GenericContainer<*>, prefix: String = "vscode-kotlin"): List<VSCodeLogArtifact> {
        val dynamicLogs = execContainerShell(
            container,
            30,
            "if [ -d /tmp/vscode-log ]; then find /tmp/vscode-log -maxdepth 4 -type f -print | sort | head -80; fi",
        )
        val paths = (
            listOf("/tmp/vscode-run.log") +
                if (dynamicLogs.exitCode == 0) {
                    dynamicLogs.stdout.lineSequence().filter { it.isNotBlank() }.toList()
                } else {
                    emptyList()
                }
            ).distinct()
        return paths.mapNotNull { path ->
            val result = execContainerShell(
                container,
                30,
                "if [ -f '$path' ]; then cat '$path'; fi",
            )
            if (result.exitCode == 0 && result.stdout.isNotEmpty()) {
                VSCodeLogArtifact(fileName = vscodeLogArtifactName(prefix, path), text = result.stdout)
            } else {
                null
            }
        }
    }

    private fun vscodeRunLog(container: GenericContainer<*>): String =
        execContainerShell(container, 30, "cat /tmp/vscode-run.log 2>/dev/null || true").stdout

    private fun vscodeLogArtifactName(prefix: String, path: String): String {
        val cleaned = path
            .removePrefix("/tmp/")
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "-")
            .trim('-')
        return "$prefix-$cleaned"
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

    private fun dumpVSCodeVisualDiff(
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

    private fun vscodeVisualRegionMetrics(
        text: String,
        expected: VisualCapture,
        actualRobot: VisualCapture,
        actualSvg: VisualCapture,
    ): String {
        val window = largestMappedRootChildWindow(text)
        return buildString {
            appendLine("screen=${expected.width}x${expected.height}")
            appendLine("expectedFullScreen=$expected")
            appendLine("robotFullScreen=$actualRobot")
            appendLine("svgFullScreen=$actualSvg")
            if (window == null) {
                appendLine("vscodeWindow=none")
                return@buildString
            }
            appendLine("vscodeWindow=${window.x},${window.y} ${window.width}x${window.height}")
            appendLine("vscodeWindowArea=${window.width * window.height}")
            appendLine("topMargin=${window.y}")
            appendLine("leftMargin=${window.x}")
            appendLine("rightMargin=${(expected.width - window.x - window.width).coerceAtLeast(0)}")
            appendLine("bottomMargin=${(expected.height - window.y - window.height).coerceAtLeast(0)}")
            appendLine("expectedInsideWindow=${regionCapture(expected.image, window)}")
            appendLine("robotInsideWindow=${regionCapture(actualRobot.image, window)}")
            appendLine("svgInsideWindow=${regionCapture(actualSvg.image, window)}")
            appendLine("expectedOutsideWindowNonWhitePixels=${outsideRegionNonWhitePixels(expected.image, window)}")
            appendLine("robotOutsideWindowNonWhitePixels=${outsideRegionNonWhitePixels(actualRobot.image, window)}")
            appendLine("svgOutsideWindowNonWhitePixels=${outsideRegionNonWhitePixels(actualSvg.image, window)}")
            appendLine(
                "robotInsideWindowCoverageRatio=${
                    ratio(
                        regionCapture(actualRobot.image, window).nonWhitePixels,
                        regionCapture(expected.image, window).nonWhitePixels,
                    )
                }",
            )
            appendLine(
                "svgInsideWindowCoverageRatio=${
                    ratio(
                        regionCapture(actualSvg.image, window).nonWhitePixels,
                        regionCapture(expected.image, window).nonWhitePixels,
                    )
                }",
            )
            appendLine("robotInsideWindowSampledDistance=${imageDistance(regionImage(expected.image, window), regionImage(actualRobot.image, window))}")
            appendLine("svgInsideWindowSampledDistance=${imageDistance(regionImage(expected.image, window), regionImage(actualSvg.image, window))}")
            appendLine("robotMismatchBounds=${mismatchBounds(expected.image, actualRobot.image).toMetricString()}")
            appendLine("svgMismatchBounds=${mismatchBounds(expected.image, actualSvg.image).toMetricString()}")
            appendLine("robotInsideWindowMismatchBounds=${mismatchBounds(regionImage(expected.image, window), regionImage(actualRobot.image, window)).toMetricString()}")
            appendLine("svgInsideWindowMismatchBounds=${mismatchBounds(regionImage(expected.image, window), regionImage(actualSvg.image, window)).toMetricString()}")
        }
    }

    private fun largestMappedRootChildWindow(text: String): Rectangle? {
        val match = largestMappedRootChildWindowMatch(text) ?: return null
        return Rectangle(
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
        )
    }

    private fun largestMappedRootChildWindowId(text: String): String? =
        largestMappedRootChildWindowMatch(text)?.groupValues?.get(1)

    private fun largestMappedRootChildWindowMatch(text: String): MatchResult? {
        val rootId = Regex("""-\s+(0x[0-9a-fA-F]+)\s+parent=\S+\s+label="root"""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: return null
        return Regex(
            """-\s+(0x[0-9a-fA-F]+)\s+parent=${Regex.escape(rootId)}\b[^\n]*\bgeometry=(-?\d+),(-?\d+)\s+(\d+)x(\d+)[^\n]*\bmapped=true\b""",
        )
            .findAll(text)
            .filter { match ->
                val width = match.groupValues[4].toInt()
                val height = match.groupValues[5].toInt()
                width > 0 && height > 0
            }
            .maxByOrNull { match -> match.groupValues[4].toLong() * match.groupValues[5].toLong() }
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

    private fun vscodeDiagnosticsSummary(text: String, logs: List<VSCodeLogArtifact>): String =
        buildString {
            appendLine("vscodeWindowEvidence=${hasVSCodeWindowEvidence(text)}")
            appendLine("vscodeUnsupportedRequests=${unsupportedRequestsFromText(text).joinToStringOrNone()}")
            appendLine("vscodeUnsupportedExtensions=${extensionQueriesFromText(text, supported = false).joinToStringOrNone()}")
            appendLine("vscodeSupportedExtensions=${extensionQueriesFromText(text, supported = true).joinToStringOrNone()}")
            appendLine("vscodeGlxOperations=${glxOperationsFromText(text).joinToStringOrNone()}")
            appendLine("vscodeServerGlxExtensions=${serverGlxExtensionsFromText(text).joinToStringOrNone()}")
            appendLine("vscodeClientGlxExtensions=${clientGlxExtensionsFromText(text).joinToStringOrNone()}")
            appendLine("vscodeDbusLogWarnings=${logs.any { it.text.contains("dbus", ignoreCase = true) }}")
        }

    private fun List<String>.joinToStringOrNone(): String =
        if (isEmpty()) "None" else joinToString(" ")

    private fun unsupportedRequestsFromText(text: String): List<String> =
        sectionLines(text, "Unsupported requests:")
            .map { it.removePrefix("-").trim() }
            .filter { it.isNotEmpty() && it != "None." }
            .distinct()
            .sorted()

    private fun assertNoVSCodeUnsupportedRequests(text: String, label: String) {
        assertTrue(
            text.lineSequence().any { it.trim() == "Unsupported requests:" },
            "$label should expose unsupported-request diagnostics\n$text",
        )
        assertTrue(
            sectionLines(text, "Unsupported requests:").isNotEmpty(),
            "$label should expose at least one unsupported-request diagnostic line\n$text",
        )
        val unsupported = unsupportedRequestsFromText(text)
        assertTrue(
            unsupported.isEmpty(),
            "$label should not leave unsupported protocol requests in the target-client trace: ${unsupported.joinToString(" ")}\n$text",
        )
    }

    private fun extensionQueriesFromText(text: String, supported: Boolean): List<String> =
        sectionLines(text, "Extension queries:")
            .mapNotNull { line ->
                val match = Regex("""-\s+#\d+\s+(\S+)\s+supported=(true|false)""").find(line.trim()) ?: return@mapNotNull null
                match.groupValues[1].takeIf { match.groupValues[2].toBooleanStrict() == supported }
            }
            .distinct()
            .sorted()

    private fun glxOperationsFromText(text: String): List<String> =
        sectionLines(text, "GLX operations:")
            .mapNotNull { line -> Regex("""-\s+#\d+\s+(\S+)""").find(line.trim())?.groupValues?.get(1) }
            .distinct()
            .sorted()

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

    private fun sectionLines(text: String, header: String): List<String> {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return emptyList()
        return lines
            .asSequence()
            .drop(start + 1)
            .takeWhile { line -> line.isBlank() || line.trimStart().startsWith("-") }
            .filter { it.trimStart().startsWith("-") }
            .toList()
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
            ?: error("XVSCodeRobotCapture did not print PNG_BASE64, stdout:\n$stdout")
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            ?: error("XVSCodeRobotCapture PNG was not readable")
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

    private fun assertVSCodeVisualClose(expected: VisualCapture, actual: VisualCapture, label: String) {
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
    )

    private data class VSCodeVisualSnapshot(
        val stats: List<VSCodeImageStats>,
        val text: String,
    )

    private data class VSCodeImageStats(
        val id: String,
        val width: Int,
        val height: Int,
        val nonWhitePixels: Int,
        val distinctNonWhiteColors: Int,
        val samples: List<String>,
    ) {
        fun hasVisibleContent(): Boolean =
            width >= 320 &&
                height >= 200 &&
                nonWhitePixels > 1_000 &&
                distinctNonWhiteColors >= 3
    }

    private data class VSCodeLogArtifact(
        val fileName: String,
        val text: String,
    )

    private data class VSCodeReferenceCapture(
        val robot: VisualCapture,
        val logs: List<VSCodeLogArtifact>,
    )

    private data class VSCodeKotlinCapture(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val html: String,
        val svgLayers: List<SvgLayer>,
        val logs: List<VSCodeLogArtifact>,
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
}
