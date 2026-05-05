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
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep API models
-keep class com.follett.keyboard.api.AgentIntent { *; }
-keep class com.follett.keyboard.api.AgentResponse { *; }

# Keep InputMethodService
-keep class com.follett.keyboard.ime.DominionKeyboardIME { *; }
