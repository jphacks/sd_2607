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
rootProject.name = "MNDT"
include(":bundle-core", ":mesh-android", ":app", ":dtn7-bpv7")
project(":dtn7-bpv7").projectDir = file("../../third_party/sd_2607-dtn7-kotlin/bpv7")
