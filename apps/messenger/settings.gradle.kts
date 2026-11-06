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
    }
}
rootProject.name = "hanashi"
include(":app", ":mosimo-dm")
// The Mosimo mesh SDK is reused in place from apps/android, not copied.
include(":bundle-core", ":mesh-android", ":dtn7-bpv7")
project(":bundle-core").projectDir = file("../android/bundle-core")
project(":mesh-android").projectDir = file("../android/mesh-android")
project(":dtn7-bpv7").projectDir = file("../../third_party/sd_2607-dtn7-kotlin/bpv7")
