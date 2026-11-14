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
// U11: forwarding benchmark, kept out of :core:test; regenerates its section of docs/NUMBERS.md.
tasks.test { exclude("mesh/bench/**") }
tasks.register<Test>("bench") {
    description = "U11 forwarding benchmark; writes docs/NUMBERS.md"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("mesh.bench.*") }
    systemProperty("bench.out", rootProject.file("docs/NUMBERS.md").absolutePath)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
