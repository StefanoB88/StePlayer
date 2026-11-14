# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.steplayer.shared.**$$serializer { *; }
-keepclassmembers class com.steplayer.shared.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- SQLDelight / SQLite ---
-keep class app.cash.sqldelight.** { *; }
-keep class androidx.sqlite.** { *; }

# --- Cast options provider is referenced from the manifest by name ---
-keep class com.steplayer.app.cast.CastOptionsProvider { *; }

# --- WorkManager worker (instantiated by reflection) ---
-keep class com.steplayer.app.download.DownloadWorker { *; }
