import java.time.Duration
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

fun safeIntellijMigrationCacheDirectory(cache: Path): Path {
    val lexical = cache.toAbsolutePath().normalize()
    check(lexical.parent != null && lexical != lexical.root) {
        "Refusing unsafe IntelliJ migration cache path: $cache"
    }
    Files.createDirectories(lexical)
    val resolved = lexical.toRealPath()
    check(resolved.parent != null && resolved != resolved.root) {
        "Refusing IntelliJ migration cache path that resolves to filesystem root: $cache"
    }
    return resolved
}

fun migrateDirectoryContents(legacy: Path, cache: Path) {
    if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) return
    val safeCache = safeIntellijMigrationCacheDirectory(cache)
    Files.list(legacy).use { entries ->
        entries.forEach { entry ->
            val target = safeCache.resolve(entry.fileName.toString())
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { Files.move(entry, target) }
                    .onFailure {
                        if (
                            Files.exists(entry, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        ) {
                            throw it
                        }
                    }
            }
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "org.jonnyzzz"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

application {
    mainClass.set("org.jonnyzzz.xserver.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
}

val migrateLegacyIntellijCache by tasks.registering {
    group = "build setup"
    description = "Moves the old cleanable IntelliJ cache into the persistent host cache before clean."
    doLast {
        val configured = providers.systemProperty("x.intellijCacheDir").orNull
            ?: providers.environmentVariable("IDEA_CACHE_DIR").orNull
        if (configured.isNullOrBlank()) {
            migrateDirectoryContents(
                layout.projectDirectory.dir("build/tmp/intellij-community-smoke/idea-cache").asFile.toPath(),
                layout.projectDirectory.dir(".gradle/intellij-community-cache").asFile.toPath(),
            )
        }
    }
}

tasks.named("clean") {
    dependsOn(migrateLegacyIntellijCache)
}

tasks.test {
    dependsOn(migrateLegacyIntellijCache)
    useJUnitPlatform()
    listOf(
        "x.intellijSmoke",
        "x.intellijParity",
        "x.intellijDebug",
        "x.intellijTraceXvfbPutImage",
        "x.intellijParityPairAttempts",
        "x.intellijUrl",
        "x.intellijImage",
        "x.intellijReferenceImage",
        "x.intellijCacheDir",
        "x.guiArtifactsDir",
        "x.intellijXvfbExtraArgs",
        "x.vscodeSmoke",
        "x.vscodeParity",
        "x.vscodeUrl",
        "x.vscodeImage",
        "x.vscodeReferenceImage",
        "x.trace",
    ).forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}

val verifyIntellijCacheMigration by tasks.registering {
    group = "verification"
    description = "Verifies no-clobber legacy IntelliJ cache migration, including dangling symlinks."
    doLast {
        val root = Files.createTempDirectory(temporaryDir.toPath(), "migration-")
        val legacy = root.resolve("legacy")
        val cache = root.resolve("cache")
        Files.createDirectories(legacy)
        Files.createDirectories(cache)
        Files.writeString(legacy.resolve("archive.tar.gz"), "archive")
        Files.writeString(legacy.resolve("preserved.txt"), "legacy")
        Files.writeString(cache.resolve("preserved.txt"), "persistent")
        Files.createSymbolicLink(legacy.resolve("dangling"), Path.of("missing-target"))

        migrateDirectoryContents(legacy, cache)

        check(Files.readString(cache.resolve("archive.tar.gz")) == "archive")
        check(Files.readString(cache.resolve("preserved.txt")) == "persistent")
        check(Files.isSymbolicLink(cache.resolve("dangling")))
        check(!Files.exists(legacy.resolve("archive.tar.gz"), LinkOption.NOFOLLOW_LINKS))
        check(Files.exists(legacy.resolve("preserved.txt"), LinkOption.NOFOLLOW_LINKS))

        val linkedTarget = root.resolve("linked-target")
        val linkedLegacy = root.resolve("linked-legacy")
        val linkedCache = root.resolve("linked-cache")
        Files.createDirectories(linkedTarget)
        Files.writeString(linkedTarget.resolve("must-stay.txt"), "linked")
        Files.createSymbolicLink(linkedLegacy, linkedTarget)
        migrateDirectoryContents(linkedLegacy, linkedCache)
        check(Files.readString(linkedTarget.resolve("must-stay.txt")) == "linked")
        check(!Files.exists(linkedCache, LinkOption.NOFOLLOW_LINKS))
        Files.delete(linkedLegacy)

        val unsafeLegacy = root.resolve("unsafe-legacy")
        val unsafeCache = root.resolve("unsafe-cache")
        Files.createDirectories(unsafeLegacy)
        Files.writeString(unsafeLegacy.resolve("must-stay.txt"), "unsafe")
        Files.createSymbolicLink(unsafeCache, root.root)
        val unsafeFailure = runCatching { migrateDirectoryContents(unsafeLegacy, unsafeCache) }.exceptionOrNull()
        check(unsafeFailure is IllegalStateException)
        check(Files.readString(unsafeLegacy.resolve("must-stay.txt")) == "unsafe")
        Files.delete(unsafeCache)
    }
}

tasks.check {
    dependsOn(verifyIntellijCacheMigration)
}

tasks.register<Exec>("dockerBuildX11Client") {
    group = "verification"
    description = "Builds the Docker image with X11 and IntelliJ runtime dependencies."
    commandLine(
        "docker",
        "build",
        "-t",
        "jonnyzzz-x/x11-client:latest",
        "docker/x11-client",
    )
}

tasks.register<Exec>("dockerBuildX11Reference") {
    group = "verification"
    description = "Builds the Docker image with Xvfb for reference-only comparison tests."
    dependsOn("dockerBuildX11Client")
    commandLine(
        "docker",
        "build",
        "-t",
        "jonnyzzz-x/x11-reference:latest",
        "docker/x11-reference",
    )
}

tasks.register("dockerBuildX11Images") {
    group = "verification"
    description = "Builds all local Docker images used by the X11 integration tests."
    dependsOn("dockerBuildX11Client", "dockerBuildX11Reference")
}

tasks.register<Exec>("testRunAgentProcessGroup") {
    group = "verification"
    description = "Verifies timeout cleanup for reparented run-agent workers."
    timeout.set(Duration.ofMinutes(2))
    commandLine("scripts/test-run-agent-process-group.sh")
}

val verifyKotlinTestSources by tasks.registering {
    group = "verification"
    description = "Rejects non-Kotlin JVM and Python test sources; tests must be native Kotlin/JUnit."
    val testSources = layout.projectDirectory.dir("src/test")
    inputs.dir(testSources)
    doLast {
        val nonKotlinSources = fileTree(testSources) {
            include("**/*.java", "**/*.groovy", "**/*.scala", "**/*.py", "**/*.pyw")
        }.files.sortedBy { it.invariantSeparatorsPath }
        check(nonKotlinSources.isEmpty()) {
            "Non-Kotlin test sources are not allowed; port these files to Kotlin/JUnit:\n" +
                nonKotlinSources.joinToString("\n") { "- ${it.relativeTo(projectDir).invariantSeparatorsPath}" }
        }
    }
}

tasks.named("check") {
    dependsOn("testRunAgentProcessGroup")
    dependsOn(verifyKotlinTestSources)
}
