plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.opencode.android"
    compileSdk = 36

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

    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
        getByName("androidTest") { java.srcDirs("src/androidTest/kotlin") }
    }

    testOptions {
        unitTests {
            // Lets JVM unit tests touch android.util.Log without an "not mocked"
            // failure. The logic under test is pure Kotlin regardless.
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        // Reports are uploaded by CI; keep both formats.
        htmlReport = true
        xmlReport = true
    }
}

// The modern compilerOptions DSL. `kotlinOptions` is deprecated in Kotlin 2.2 and
// its deprecation level has been rising, so it is avoided rather than suppressed.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
