plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.puhetrack.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.puhetrack.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "2.14.4"

        // Real phones only (x86/x86_64 are emulators) — halves APK size
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                cFlags += "-std=c11"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Secrets live in ~/.gradle/gradle.properties or env vars,
        // never in the repo. Register only when the keystore exists.
        // (Imperative style: avoids nested-lambda receiver issues.)
        val ksFile = providers.gradleProperty("PUHE_STORE_FILE")
            .orElse(providers.environmentVariable("PUHE_STORE_FILE"))
            .map { file(it) }
        if (ksFile.isPresent && ksFile.get().exists()) {
            val play = create("playRelease")
            play.storeFile = ksFile.get()
            play.storePassword = providers.gradleProperty("PUHE_STORE_PASSWORD")
                .orElse(providers.environmentVariable("PUHE_STORE_PASSWORD")).get()
            play.keyAlias = providers.gradleProperty("PUHE_KEY_ALIAS")
                .orElse(providers.environmentVariable("PUHE_KEY_ALIAS")).get()
            play.keyPassword = providers.gradleProperty("PUHE_KEY_PASSWORD")
                .orElse(providers.environmentVariable("PUHE_KEY_PASSWORD")).get()
        }
    }

    buildTypes {
        release {
            // R8 shrinking enabled: strips dead code and shrinks resources.
            // Keep rules for JNI-facing sherpa-onnx classes live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            // Never distribute a release signed with the debug identity. A configured
            // upload key signs this build; otherwise Gradle emits an unsigned artifact.
            signingConfig = signingConfigs.findByName("playRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines (ML Kit await(), model download, chunked engines)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // On-device Finnish -> English translation, no API key needed
    implementation("com.google.mlkit:translate:17.0.3")

    // On-device Finnish speech recognition (single-mic, works while recording).
    // NVIDIA Parakeet-tdt-0.6b-v3 via sherpa-onnx (Finnish verified, CC-BY-4.0).
    // Model files (~670MB) download on first run, see SherpaBatch.kt.
    // (Vosk was removed: no Finnish model exists — its URL 404s.)
    // (Azure Speech SDK removed: unused, ~50-80MB of native libs per ABI.)
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.7")

    // Encrypted storage for the optional cloud speech key
    implementation("androidx.security:security-crypto:1.1.0")

    // HTTP for OpenAI Whisper REST engine
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Single source of truth: versionName/versionCode live here.
// Run ./gradlew :app:syncProductDocs to stamp them into PRODUCT.md.
tasks.register("syncProductDocs") {
    group = "documentation"
    description = "Sync PRODUCT.md version header + artifact sizes from versionName/versionCode and build outputs."
    doLast {
        val vName = android.defaultConfig.versionName
        val vCode = android.defaultConfig.versionCode
        val doc = rootDir.resolve("PRODUCT.md")
        var s = doc.readText()
        s = s.replace(
            Regex("\\*\\*Version:\\*\\* .*"),
            "**Version:** $vName (versionCode $vCode) · **Package:** `com.puhetrack.app`  "
        )

        // Artifact sizes come from actual build outputs, never by hand.
        fun bytes(f: java.io.File): String = if (f.exists()) "${f.length()} bytes" else "not built"
        val rel = project.layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val aab = project.layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        val artLine = Regex("\\*\\*Measured final artifacts:\\*\\*.*")
        val artNew = "**Measured final artifacts:** release APK ${bytes(rel)}; release AAB ${bytes(aab)}."
        s = if (artLine.containsMatchIn(s)) s.replace(artLine, artNew)
        else s.replace("**Primary languages:**", "$artNew\n\n**Primary languages:**")
        doc.writeText(s)
        println("PRODUCT.md synced -> $vName ($vCode); $artNew")
    }
}
