plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.jisaku.mosimo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.jisaku.mosimo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["mosimoTestPeerNode"] = providers.gradleProperty("mosimoTestPeerNode").orElse("").get()
        manifestPlaceholders["mosimoTestAuthor"] = providers.gradleProperty("mosimoTestAuthor").orElse("").get()
        manifestPlaceholders["mosimoTestNode"] = providers.gradleProperty("mosimoTestNode").orElse("").get()
    }

    signingConfigs {
        create("release") {
            storeFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
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
