package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VSCodeSmokeTest {
    @Test
    fun `vscode diagnostics summary extracts extension and glx evidence`() {
        val text = """
            Window hierarchy and geometry:
            - 0x200003 parent=0x26 label="code" geometry=0,0 1279x899 class=InputOutput depth=24 visual=0x28 backgroundPixel=-592396 backgroundPixmap=none borderPixel=0 borderPixmap=none bitGravity=1 winGravity=1 backingStore=0 backingPlanes=-1 backingPixel=0 saveUnder=false overrideRedirect=false colormap=0x27 cursor=0x20000d mapped=true focused=true stack=4

            Extension queries:
            - #57 XInputExtension supported=false
            - #56 XFIXES supported=true
            - #55 SYNC supported=true
            - #51 RENDER supported=true
            - #49 GLX supported=true
            - #48 DRI3 supported=false

            Unsupported requests:
            - None.

            GLX operations:
            - #8 QueryServerString minor=19 screen=0 name=2
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
        assertTrue(summary.contains("vscodeUnsupportedExtensions=DRI3 XInputExtension"), summary)
        assertTrue(summary.contains("vscodeSupportedExtensions=GLX RENDER SYNC XFIXES"), summary)
        assertTrue(summary.contains("vscodeGlxOperations=GetFBConfigs QueryServerString QueryVersion SetClientInfo2ARB"), summary)
        assertTrue(summary.contains("vscodeClientGlxExtensions=GLX_ARB_create_context GLX_ARB_create_context_profile"), summary)
        assertTrue(summary.contains("vscodeDbusLogWarnings=true"), summary)
        assertFalse(summary.contains("vscodeUnsupportedExtensions=XInputExtension DRI3"), summary)
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
        assumeTrue(imageExists(image), "Build $image first with scripts/run-gradle-bounded.sh dockerBuildX11Client")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 1280, height = 900)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withFileSystemBind(cleanProjectExport().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
                .withCommand("sleep", "900")
                .use { container ->
                    container.start()
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
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
                        dumpVSCodeArtifacts(
                            text = snapshot.text,
                            svg = httpGet(port, "/screen.svg"),
                            logs = collectVSCodeLogs(container),
                        )
                        val running = container.execInContainer("sh", "-lc", "kill -0 $(cat /tmp/vscode.pid)")
                        assertEquals(0, running.exitCode, "VSCode exited early\n${vscodeRunLog(container)}")
                        assertTrue(
                            hasVSCodeWindowEvidence(snapshot.text),
                            "VSCode smoke should expose a labeled editor window in the HTTP report\n${snapshot.text}",
                        )
                        assertTrue(
                            snapshot.text.contains("Unsupported requests:\n- None."),
                            "VSCode smoke should not leave unsupported protocol requests in the target-client trace\n${snapshot.text}",
                        )
                        assertTrue(
                            snapshot.stats.any { it.hasVisibleContent() },
                            "VSCode screen SVG should contain non-white rendered pixels, got ${snapshot.stats}\n${snapshot.text}",
                        )
                    } finally {
                        container.execInContainer("sh", "-lc", "kill $(cat /tmp/vscode.pid 2>/dev/null || pgrep -f '/opt/vscode/code') 2>/dev/null || true")
                    }
                }
            server.close()
            serverThread.join(1_000)
        }
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

    private fun hasVSCodeWindowEvidence(text: String): Boolean =
        text.contains("Visual Studio Code") ||
            text.contains("label=\"code\"") ||
            text.contains("Chromium clipboard")

    private fun pngDataUris(svg: String): List<EmbeddedPng> =
        Regex("""<image\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                val tag = match.value
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                EmbeddedPng(
                    id = id,
                    bytes = Base64.getDecoder().decode(encoded),
                )
            }
            .toList()

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

    private fun dumpVSCodeArtifacts(text: String, svg: String, logs: List<VSCodeLogArtifact>) {
        val directory = vscodeSmokeArtifactsDirectory()
        File(directory, "vscode-kotlin-text.txt").writeText(text)
        File(directory, "vscode-kotlin-screen.svg").writeText(svg)
        File(directory, "vscode-diagnostics.txt").writeText(vscodeDiagnosticsSummary(text, logs))
        logs.forEach { artifact -> File(directory, artifact.fileName).writeText(artifact.text) }
    }

    private fun dumpVSCodeArtifactsBestEffort(container: GenericContainer<*>, port: Int, failure: Throwable) {
        val text = runCatching { httpGet(port, "/text.txt") }
            .getOrElse { "Failed to fetch /text.txt: ${it::class.qualifiedName}: ${it.message}" }
        val svg = runCatching { httpGet(port, "/screen.svg") }
            .getOrElse { "<!-- Failed to fetch /screen.svg: ${it::class.qualifiedName}: ${it.message} -->" }
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
        dumpVSCodeArtifacts(text = text, svg = svg, logs = logs)
    }

    private fun vscodeSmokeArtifactsDirectory(): File =
        projectRoot().resolve("build/tmp/vscode-smoke").toFile().also { it.mkdirs() }

    private fun collectVSCodeLogs(container: GenericContainer<*>): List<VSCodeLogArtifact> {
        val dynamicLogs = container.execInContainer(
            "sh",
            "-lc",
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
            val result = container.execInContainer(
                "sh",
                "-lc",
                "if [ -f '$path' ]; then cat '$path'; fi",
            )
            if (result.exitCode == 0 && result.stdout.isNotEmpty()) {
                VSCodeLogArtifact(fileName = vscodeLogArtifactName(path), text = result.stdout)
            } else {
                null
            }
        }
    }

    private fun vscodeRunLog(container: GenericContainer<*>): String =
        container.execInContainer("sh", "-lc", "cat /tmp/vscode-run.log 2>/dev/null || true").stdout

    private fun vscodeLogArtifactName(path: String): String {
        val cleaned = path
            .removePrefix("/tmp/")
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "-")
            .trim('-')
        return "vscode-kotlin-$cleaned"
    }

    private fun vscodeDiagnosticsSummary(text: String, logs: List<VSCodeLogArtifact>): String =
        buildString {
            appendLine("vscodeWindowEvidence=${hasVSCodeWindowEvidence(text)}")
            appendLine("vscodeUnsupportedRequests=${unsupportedRequestsFromText(text).joinToStringOrNone()}")
            appendLine("vscodeUnsupportedExtensions=${extensionQueriesFromText(text, supported = false).joinToStringOrNone()}")
            appendLine("vscodeSupportedExtensions=${extensionQueriesFromText(text, supported = true).joinToStringOrNone()}")
            appendLine("vscodeGlxOperations=${glxOperationsFromText(text).joinToStringOrNone()}")
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
}
