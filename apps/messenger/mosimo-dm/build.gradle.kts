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
    api(project(":bundle-core"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}
tasks.test {
    systemProperty("fr.acinq.secp256k1.tmpdir", System.getProperty("java.io.tmpdir"))
}
// Desktop JNI 0.24 runs on JDK 21; the library's bytecode stays at 17.
configurations.testRuntimeClasspath {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
