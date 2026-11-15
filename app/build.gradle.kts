plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.meshnet.offline"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.meshnet.offline"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/io.netty.versions.properties") } }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":lan"))
    implementation(project(":uiserver"))
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.core)
    testImplementation(kotlin("test"))
}
