# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools.

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keep class com.moex.widget.data.** { *; }

# Keep WorkManager
-keep class androidx.work.** { *; }