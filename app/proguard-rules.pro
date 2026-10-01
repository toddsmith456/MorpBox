# MorpBox — R8 rules for the release build.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class kotlin.Metadata { *; }

# YouniversalThemeState persists these enums by name.
-keepclassmembers enum dev.youniversal.theme.YouniversalBackgroundStyle { *; }
-keepclassmembers enum dev.youniversal.theme.YouniversalContrast { *; }

# BouncyCastle: keep the secp256k1 / ChaCha / HKDF engines we call by class name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn javax.naming.**

# OkHttp / platform
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# Tink
-keep class com.google.crypto.tink.** { *; }

# JSON envelopes and Nostr events are constructed reflectively-adjacent via org.json.
-keepclassmembers class org.json.** { *; }
