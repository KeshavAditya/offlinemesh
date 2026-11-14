// core must only use JVM APIs that exist on Android API 29 (no HexFormat, java.net.http, virtual threads, android.*).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
