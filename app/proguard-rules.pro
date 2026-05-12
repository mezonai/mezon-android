-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-assumenosideeffects class android.util.Log {
    public static *** v(...);
}

-keep class com.mezon.mezon.api.** { *; }
-keep class com.mezon.mezon.rtapi.** { *; }
-dontwarn com.google.protobuf.**

-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-keep class org.jni_zero.** { *; }
-keep interface org.jni_zero.** { *; }
-keepclassmembers class org.jni_zero.** { *; }
-keep,allowobfuscation,allowshrinking class org.jni_zero.JniInit
-keepclassmembers,includedescriptorclasses class org.jni_zero.JniInit {
    private static java.lang.Object[] init();
    private static void crashIfMultiplexingMisaligned(long, long);
}
-dontwarn org.jni_zero.**

-keep class io.livekit.** { *; }
-keep interface io.livekit.** { *; }
-keepclassmembers class io.livekit.** { *; }
-dontwarn io.livekit.**

-keep class livekit.** { *; }
-keep interface livekit.** { *; }
-keepclassmembers class livekit.** { *; }
-keepclassmembers,includedescriptorclasses class livekit.org.jni_zero.JniInit {
    private static java.lang.Object[] init();
    private static void crashIfMultiplexingMisaligned(long, long);
}
-dontwarn livekit.**

-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.jni_zero.** { *; }
-keep class livekit.org.webrtc.** { *; }
-keep class livekit.org.jni_zero.** { *; }
