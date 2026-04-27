-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

-keep class com.mezon.mezon.api.** { *; }
-keep class com.mezon.mezon.rtapi.** { *; }
-dontwarn com.google.protobuf.**
