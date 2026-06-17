-keep class com.heartflow.app.** { *; }
-keep class com.heartflow.data.** { *; }
-keep class com.heartflow.engine.** { *; }
-keep class com.heartflow.memory.** { *; }

-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

-keep class org.json.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class * extends java.io.Serializable { *; }
-keepclassmembers class * {
    public <init>(...);
}
