plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(17) }
application { mainClass.set("mesh.desktop.MainKt") }
dependencies {
    implementation(project(":core"))
    implementation(project(":lan"))
    implementation(project(":uiserver"))
}
