# BitperfectPlayer ProGuard rules

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── AndroidX / Leanback ──────────────────────────────────────────────────────
-keep class androidx.leanback.** { *; }

# ── Media3 / ExoPlayer ───────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── jcifs-ng (SMB library) ───────────────────────────────────────────────────
-keep class jcifs.** { *; }
-dontwarn jcifs.**
# jcifs uses reflection internally
-keepattributes Signature,*Annotation*

# ── OkHttp / Okio (pulled in by media3-datasource-okhttp) ───────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── Keep BuildConfig ─────────────────────────────────────────────────────────
-keep class com.example.bitperfectplayer.BuildConfig { *; }

# ── Keep our own app classes ─────────────────────────────────────────────────
-keep class com.example.bitperfectplayer.** { *; }

# ── Suppress common library warnings ─────────────────────────────────────────
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn com.google.errorprone.**

# ── BouncyCastle (Required for SMB MD4) ──────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-keep class jcifs.** { *; }
