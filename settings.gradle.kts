pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx Android AAR (official dep used by their own demo app)
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "PuheTrack"
include(":app")
