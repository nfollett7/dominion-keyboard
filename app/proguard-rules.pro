# Keep Room entities
-keep class com.follett.keyboard.data.model.** { *; }
-keep class com.follett.keyboard.data.db.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Keep Kotlin coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
