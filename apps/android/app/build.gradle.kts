plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.mndt.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.mndt.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-experimental"
    }
    flavorDimensions += "demo"
    productFlavors {
        create("demo") { dimension = "demo" }
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
    buildFeatures { compose = true }
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}
