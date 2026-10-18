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
    api(project(":dtn7-bpv7"))
    api("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.21.3")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jvm:0.24.0")
    testRuntimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.processTestResources {
    from("../../esp32/test_bundle") { include("wire-v1-combined-server-fixture.json") }
}
tasks.test {
    systemProperty("fr.acinq.secp256k1.tmpdir", System.getProperty("java.io.tmpdir"))
}
// Desktop JNI 0.24 runs on JDK 21; the reusable library's bytecode stays at 17.
configurations.testRuntimeClasspath {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
