-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-assumenosideeffects class android.util.Log {
    public static *** v(...);
}

-keep class com.mezon.mezon.api.** { *; }
-keep class com.mezon.mezon.rtapi.** { *; }
-dontwarn com.google.protobuf.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

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

-keepclasseswithmembernames class * {
    native <methods>;
}
