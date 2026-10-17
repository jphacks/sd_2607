plugins {
    kotlin("jvm")
    `java-library`
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    api("org.slf4j:slf4j-api:1.7.36")
    api("org.bouncycastle:bcprov-jdk18on:1.83")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.21.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
}
