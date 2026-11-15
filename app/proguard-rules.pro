# R8 rules for the release APK (deploy-all installs it). kotlinx.serialization and coroutines ship their own rules.
# Ktor server: engine and plugins are wired through ServiceLoader and reflection on names.
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.config.** { *; }
-keep class io.ktor.server.websocket.** { *; }
-keep class io.ktor.websocket.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
# Desktop-only references inside Ktor, typesafe-config and slf4j.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn com.typesafe.**
-dontwarn org.fusesource.jansi.**
-dontwarn io.netty.**
-dontwarn org.jetbrains.annotations.**
# Our @Serializable wire/UI types: keep names so serialized field names and polymorphic discriminators never change.
-keep @kotlinx.serialization.Serializable class mesh.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
# MainActivity.Bridge: the page calls these by name (OMNative.state/set/open).
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
