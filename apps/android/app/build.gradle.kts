plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.jisaku.mosimo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.jisaku.mosimo"
        minSdk = 26
        targetSdk = 36
        // CI passes the Actions run number; local builds fall back to the manual value.
        versionCode = providers.gradleProperty("mosimoVersionCode").orNull?.toInt() ?: 3
        versionName = "0.2.3"
        manifestPlaceholders["mosimoTestPeerNode"] = providers.gradleProperty("mosimoTestPeerNode").orElse("").get()
        manifestPlaceholders["mosimoTestAuthor"] = providers.gradleProperty("mosimoTestAuthor").orElse("").get()
        manifestPlaceholders["mosimoTestNode"] = providers.gradleProperty("mosimoTestNode").orElse("").get()
        manifestPlaceholders["mosimoPlcRegistration"] = providers.gradleProperty("mosimoPlcRegistration").orElse("true").get()
        manifestPlaceholders["mosimoTestPublicRelay"] = ""
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Installs next to a store/CI release build on the same phone without touching its keys and posts.
        getByName("debug") {
            applicationIdSuffix = providers.gradleProperty("mosimoDebugSuffix").orElse(".debug").get()
            versionNameSuffix = "-debug"
            // Local public-information checks against a test relay (e.g. ws://127.0.0.1:7777 over `adb reverse`). Release builds ignore it.
            manifestPlaceholders["mosimoTestPublicRelay"] = providers.gradleProperty("mosimoTestPublicRelay").orElse("").get()
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("tools.fastlane:screengrab:2.1.1")
}
