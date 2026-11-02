plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "org.mndt.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.mndt.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("android.injected.version.code").orElse("1").get().toInt()
        versionName = providers.gradleProperty("android.injected.version.name").orElse("0.2.0").get()
        manifestPlaceholders["mndtTestPeerNode"] = providers.gradleProperty("mndtTestPeerNode").orElse("").get()
        manifestPlaceholders["mndtTestAuthor"] = providers.gradleProperty("mndtTestAuthor").orElse("").get()
        // Empty until the policy page is published; the settings row is hidden while empty.
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"\"")
        manifestPlaceholders["mndtTestNode"] = providers.gradleProperty("mndtTestNode").orElse("").get()
    }
    flavorDimensions += "demo"
    productFlavors {
        create("demo") {
            dimension = "demo"
            applicationId = "dev.oriom.mosimo"
        }
        create("peer") {
            dimension = "demo"
            applicationIdSuffix = ".peer"
            versionNameSuffix = "-peer"
        }
        create("verification") {
            dimension = "demo"
            applicationId = "org.mndt.demo.verification"
            versionNameSuffix = "-verification"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":mesh-android"))
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation3:navigation3-runtime:1.1.7")
    implementation("androidx.navigation3:navigation3-ui:1.1.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("io.insert-koin:koin-androidx-compose:4.1.1")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    testImplementation("junit:junit:4.13.2")
}
