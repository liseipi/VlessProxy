# VLESS Proxy ProGuard Rules
-keep class com.vlessproxy.model.** { *; }
-keep class com.vlessproxy.service.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Java-WebSocket
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
