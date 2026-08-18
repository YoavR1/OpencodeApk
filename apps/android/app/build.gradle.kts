plugins {
    // AGP 9 ships built-in Kotlin support; applying org.jetbrains.kotlin.android
    // on top of it is an error. See https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application)
}

android {
    namespace = "ai.opencode.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.opencode.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: the installed ID must match the documented
            // ai.opencode.android exactly (docs/ARCHITECTURE.md 2.2).
            isMinifyEnabled = false
        }
        release {
            // R8 / shrinking is deliberately deferred to M11, where the rules can
            // be written against real code and verified. Turning it on now would
            // only produce rules nothing has exercised.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
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
