pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // FIX: FAIL_ON_PROJECT_REPOS is strict — any project-level repos() block
    // will break the build. Switching to PREFER_SETTINGS is safer with
    // mixed dependencies like iText + TFLite + JitPack.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }       // MPAndroidChart
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "AIVoiceDetector"
include(":app")
