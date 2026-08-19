plugins {
    // AGP 9 ships built-in Kotlin support; applying org.jetbrains.kotlin.android
    // on top of it is an error. See https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------
// Versioning
//
// One source of truth, overridable from the environment so CI can stamp a build
// without editing a tracked file.
//
//   versionName  the human version, e.g. 0.1.0
//   versionCode  must increase monotonically for every build a device may see;
//                Android refuses to install an APK whose code is not higher.
//
// CI passes the run number, which is monotonic per repository. A local build
// gets 1, which is fine because a local build is never published.
// ---------------------------------------------------------------------------
val appVersionName: String = (findProperty("opencode.versionName") as String?)
    ?: System.getenv("OPENCODE_VERSION_NAME")
    ?: "0.1.0"

val appVersionCode: Int = ((findProperty("opencode.versionCode") as String?)
    ?: System.getenv("OPENCODE_VERSION_CODE"))
    ?.toIntOrNull()
    ?: 1

/** Signing material from the environment, or null when it is not configured. */
data class ReleaseSigning(
    val storePath: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val releaseSigning: ReleaseSigning? = run {
    val storePath = System.getenv("OPENCODE_KEYSTORE")
    val storePassword = System.getenv("OPENCODE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("OPENCODE_KEY_ALIAS")
    // A key password is commonly the same as the store password; accept that
    // rather than making the caller repeat it.
    val keyPassword = System.getenv("OPENCODE_KEY_PASSWORD") ?: storePassword

    if (storePath.isNullOrBlank() || storePassword.isNullOrBlank() || keyAlias.isNullOrBlank()) {
        null
    } else if (!File(storePath).isFile) {
        // Configured but wrong is a mistake worth stopping for: silently
        // producing an unsigned APK would look like the signing worked.
        throw GradleException(
            "OPENCODE_KEYSTORE is set to '$storePath', which is not a file. " +
                "See docs/RELEASE.md.",
        )
    } else {
        ReleaseSigning(storePath, storePassword, keyAlias, keyPassword!!)
    }
}

android {
    namespace = "ai.opencode.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.opencode.android"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ------------------------------------------------------------------ signing
    //
    // Signing material NEVER enters this repository (.claude/rules/quality.md Q8).
    // It comes from the environment, which is a CI secret store in CI and a local
    // keystore the developer owns otherwise. See docs/RELEASE.md.
    //
    // When the environment is not configured the release build is left UNSIGNED
    // rather than falling back to the debug key. A debug-signed "release" is the
    // dangerous outcome: it installs, it looks finished, and it can never be
    // upgraded by a properly signed build because the signatures do not match.
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.storePath)
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword

                // v1 is off: minSdk 26 means every supported device understands
                // v2+, and v1 (JAR signing) is the weaker scheme.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: the installed ID must match the documented
            // ai.opencode.android exactly (docs/ARCHITECTURE.md 2.2).
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // M6 spike: the instrumented feasibility test ships a real arm64 executable
    // as a jniLib to measure what an app may actually exec. Debug builds must
    // extract native libraries for that path to exist at all - with the modern
    // default they are mapped straight out of the APK and nativeLibraryDir holds
    // nothing to run. Release is untouched; nothing here ships.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            // Lets JVM unit tests touch android.util.Log without an "not mocked"
            // failure, for the tests that are pure Kotlin.
            isReturnDefaultValues = true
            // Robolectric needs the packaged android resources to run the tests
            // that exercise real platform classes.
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        // htmlReport/xmlReport are not set: AGP 9 always generates lint reports,
        // and the setters are deprecated.
    }
}

// ---------------------------------------------------------------------------
// Shared OpenCode UI
//
// From M3 the APK's web content is the real upstream SolidJS application, built
// by `packages/android` with vite. It is build output, not source: the assets
// directory is generated and git-ignored.
//
// The build deliberately FAILS when the bundle is missing rather than producing
// an APK that installs and shows a blank screen. A blank app that builds is
// worse than a build that tells you what to run.
// ---------------------------------------------------------------------------

val sharedUiDist = rootProject.layout.projectDirectory.dir("../../packages/android/dist")
val webAssetsDir = layout.projectDirectory.dir("src/main/assets/web")

// A Sync task whose source directory does not exist is skipped as NO-SOURCE, and
// a skipped task's doFirst never runs - which would let the build succeed and
// ship an APK with no web content at all. The check therefore lives in its own
// task that declares no outputs, so Gradle always runs it.
val verifySharedUi by tasks.registering {
    description = "Fails the build if the shared OpenCode UI has not been built."
    group = "verification"
    outputs.upToDateWhen { false }

    doLast {
        val index = sharedUiDist.file("index.html").asFile
        check(index.exists()) {
            """
            |
            |The shared OpenCode UI has not been built.
            |
            |Expected: ${index.path}
            |
            |Build it first, from the repository root:
            |
            |    bun install --frozen-lockfile
            |    bun run --cwd packages/android build
            |
            |CI does this in the "Build shared OpenCode UI" step before Gradle runs.
            |See docs/ARCHITECTURE.md 2.2 and docs/CURRENT_STATUS.md.
            """.trimMargin()
        }
    }
}

val syncSharedUi by tasks.registering(Sync::class) {
    description = "Copies the built shared OpenCode UI into the APK assets."
    group = "build"
    dependsOn(verifySharedUi)

    from(sharedUiDist)
    into(webAssetsDir)
}

tasks.named("preBuild") { dependsOn(syncSharedUi) }

// ---------------------------------------------------------------------------
// Release gates (M11)
//
// Two things must be true of a release APK that are true of neither a debug APK
// nor of the source tree, so neither the unit tests nor lint can check them:
//
//  1. the packaged UI was built for a shipping channel, and
//  2. every third-party binary it carries is attributed.
//
// Both fail silently in the worst way - a DEV badge on a store build, or a
// licence violation - so they are wired as build dependencies of the release
// variant rather than left to a checklist.
// ---------------------------------------------------------------------------
val verifyReleaseChannel by tasks.registering {
    description = "Fails a release build whose shared UI was built for the dev channel."
    group = "verification"
    outputs.upToDateWhen { false }

    doLast {
        val info = webAssetsDir.file("build-info.json").asFile
        // A bundle predating this check has no marker. Treat that as dev, since
        // "unknown" and "not built for release" are the same risk.
        val channel = if (info.exists()) {
            Regex(""""channel"\s*:\s*"([a-z]+)"""").find(info.readText())?.groupValues?.get(1) ?: "unknown"
        } else {
            "unknown"
        }

        check(channel == "prod" || channel == "beta") {
            """
            |
            |The packaged OpenCode UI was built for the '$channel' channel.
            |
            |Upstream defaults OPENCODE_CHANNEL to "dev", which draws a DEV badge in
            |the titlebar and enables debug tooling. Shipping that as a release was a
            |real M11 finding, caught only by looking at a screenshot.
            |
            |Rebuild the renderer for release, from the repository root:
            |
            |    bun run --cwd packages/android build:release
            |
            |See docs/RELEASE.md.
            """.trimMargin()
        }
        logger.lifecycle("Shared UI channel: $channel")
    }
}

val verifyAttribution by tasks.registering {
    description = "Fails a release build whose bundled third-party binaries are unattributed."
    group = "verification"
    outputs.upToDateWhen { false }

    val script = rootProject.layout.projectDirectory.file("../../scripts/runtime/collect-licenses.py")
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

    doLast {
        // A build without the runtime ships no third-party binary and so needs
        // no notice for one (ADR-0023).
        if (!jniLibs.asFile.isDirectory) {
            logger.lifecycle("No native libraries packaged; attribution not required.")
            return@doLast
        }

        val python = listOf("python", "python3").firstOrNull { candidate ->
            runCatching {
                providers.exec {
                    commandLine(candidate, "--version")
                    isIgnoreExitValue = true
                }.result.get().exitValue == 0
            }.getOrDefault(false)
        }
        check(python != null) {
            "python is required to verify open-source attribution; see docs/RELEASE.md"
        }

        val result = providers.exec {
            commandLine(python, script.asFile.absolutePath, "--check")
            isIgnoreExitValue = true
        }
        val output = result.standardOutput.asText.get() + result.standardError.asText.get()
        check(result.result.get().exitValue == 0) {
            """
            |
            |Open-source attribution is missing or stale:
            |
            |$output
            |Regenerate it from the repository root:
            |
            |    python scripts/runtime/collect-licenses.py
            |
            |See docs/LICENSES.md.
            """.trimMargin()
        }
        logger.lifecycle(output.trim())
    }
}

// Only the release variant. A debug build is expected to be a dev-channel build,
// and blocking local iteration on attribution would be noise.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseChannel, verifyAttribution)
}

// ---------------------------------------------------------------------------
// On-device runtime (M7)
//
// The Node runtime and the server bundle are large third-party build outputs, so
// they are prepared by scripts/runtime/prepare-android-runtime.py rather than
// committed. A build without them is legitimate - it produces the remote-only
// app M5 shipped - so this reports what kind of APK is being built instead of
// failing. The app degrades honestly: runtime.await fails, and the UI falls back
// to asking for a server.
// ---------------------------------------------------------------------------
val reportRuntime by tasks.registering {
    description = "Reports whether the on-device OpenCode runtime is packaged."
    group = "verification"
    outputs.upToDateWhen { false }

    val nativeDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    val assetsDir = layout.projectDirectory.dir("src/main/assets/runtime")

    doLast {
        val node = nativeDir.file("libnode.so").asFile
        val bundle = assetsDir.file("node.js").asFile
        if (node.exists() && bundle.exists()) {
            val native = nativeDir.asFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val assets = assetsDir.asFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            logger.lifecycle(
                "OpenCode runtime: PACKAGED (%.1f MB native + %.1f MB assets)".format(
                    native / 1e6,
                    assets / 1e6,
                ),
            )
        } else {
            logger.lifecycle(
                "OpenCode runtime: NOT packaged - this APK needs an external server. " +
                    "To include it: python scripts/runtime/prepare-android-runtime.py",
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(reportRuntime) }

// NetworkSecurityConfigTest reads the network security XML off disk, because the
// release and debug policies never coexist in one build for a runtime check to
// compare. Gradle cannot see that dependency by itself, so without this the test
// task stays UP-TO-DATE when the policy changes - which is precisely when it
// needs to run. Verified by mutating the config and watching the test fail.
tasks.withType<Test>().configureEach {
    inputs.files(
        layout.projectDirectory.dir("src/main/res/xml").asFileTree,
        layout.projectDirectory.dir("src/debug/res/xml").asFileTree,
    )
        .withPropertyName("networkSecurityConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // For ApplicationProvider in the Robolectric tests. Same artifact the
    // instrumented tests already use, so it adds no version to track.
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
