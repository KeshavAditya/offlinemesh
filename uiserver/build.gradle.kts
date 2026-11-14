plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
// Dev server on a fake node (test sources): ./gradlew :uiserver:runFake [--args=8081]
tasks.register<JavaExec>("runFake") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("mesh.ui.FakeNodeKt")
}
