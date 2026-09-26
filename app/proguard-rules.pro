# Room Browser — R8/ProGuard rules

# --- Kotlin / coroutines ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.**

# --- kotlinx.serialization ---
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.roombrowser.**$$serializer { *; }
-keepclassmembers class com.roombrowser.** { *** Companion; }
-keepclasseswithmembers class com.roombrowser.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- OkHttp / DoH ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- ZXing ---
-keep class com.google.zxing.** { *; }

# --- WebView JS interfaces (none registered, keep safe anyway) ---
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Biometric ---
-keep class androidx.biometric.** { *; }

# --- CameraX ---
-keep class androidx.camera.** { *; }
