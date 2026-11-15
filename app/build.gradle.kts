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
    // deploy-all installs release: R8-shrunk, signed with the debug key so it installs over a debug build.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/io.netty.versions.properties",
        "META-INF/native-image/**", "META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "DebugProbesKt.bin") } }
}
// jansi (ANSI console colours, desktop-only native libs) comes with ktor-server-core; nothing on Android uses it.
configurations.all { exclude(group = "org.fusesource.jansi") }
dependencies {
    implementation(project(":core"))
    implementation(project(":lan"))
    implementation(project(":uiserver"))
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.core)
    testImplementation(kotlin("test"))
}
