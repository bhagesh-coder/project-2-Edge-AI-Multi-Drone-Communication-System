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
        google()        // Google artifacts ke liye
        mavenCentral()  // WebRTC aur baaki libraries ke liye
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Server"
include(":app")