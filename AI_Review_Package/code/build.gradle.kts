plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.puhetrack.app"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.puhetrack.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "2.13.2"

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
            isMinifyEnabled = false
            // Real upload keystore when configured (see PRODUCT.md §5);
            // falls back to debug signing so local builds never break.
            signingConfig = signingConfigs.findByName("playRelease") ?: signingConfigs.getByName("debug")
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
    // Model files (~670MB) download on first run, see SherpaEngine.kt.
    // (Vosk was removed: no Finnish model exists — its URL 404s.)
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.7")

    // Encrypted storage for the optional cloud speech key
    implementation("androidx.security:security-crypto:1.1.0")

    // HTTP for Google Cloud STT / Translate, DeepL, Whisper REST engines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Cloud engines (Azure / Whisper / Google Cloud): only used when the
    // user saves the corresponding key in Settings. See AzureEngine.kt etc.
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.44.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
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
            Regex("\\*\\*Version:\\*\\* .*? · \\*\\*Package:\\*\\*"),
            "**Version:** $vName (versionCode $vCode) · **Package:**"
        )
        // Artifact sizes come from actual build outputs, never by hand.
        fun mb(f: java.io.File): String =
            if (f.exists()) "~${f.length() / 1024 / 1024} MB" else "not built"
        val dbg = project.layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val rel = project.layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val aab = project.layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        val artLine = Regex("\\*\\*Artifacts.*")
        val artNew = "**Artifacts (v$vName, measured):** `app-debug.apk` ${mb(dbg)} · " +
            "`app-release.apk` ${mb(rel)} · `app-release.aab` ${mb(aab)} (AAB is what Play Store uses)"
        s = if (artLine.containsMatchIn(s)) s.replace(artLine, artNew)
        else s.replace("**Build:**", "$artNew\n**Build:**")
        doc.writeText(s)
        println("PRODUCT.md synced -> $vName ($vCode); $artNew")
    }
}
