# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve stack-trace line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── ONNX Runtime ─────────────────────────────────────────────────────────────
# ORT uses JNI and reflection extensively; keep all public API classes.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── ONNX Runtime Extensions ──────────────────────────────────────────────────
-keep class ai.onnxruntime.extensions.** { *; }
-keepclassmembers class ai.onnxruntime.extensions.** { *; }
-dontwarn ai.onnxruntime.extensions.**

# ── opencc4j (Chinese text conversion) ───────────────────────────────────────
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn com.github.houbb.opencc4j.**

# ── FreeDroidWarn ─────────────────────────────────────────────────────────────
-keep class org.woheller69.freeDroidWarn.** { *; }
-dontwarn org.woheller69.freeDroidWarn.**

# ── android-vad ──────────────────────────────────────────────────────────────
-keep class com.konovalov.vad.** { *; }
-dontwarn com.konovalov.vad.**

# ── Guava ─────────────────────────────────────────────────────────────────────
# Guava uses reflection for some features; suppress warnings for server-only parts.
-dontwarn com.google.common.util.concurrent.internal.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# ── AndroidX Preference ───────────────────────────────────────────────────────
# Preference fragments are inflated reflectively from XML.
-keep class androidx.preference.** { *; }

# ── Speech recognition service ────────────────────────────────────────────────
# Android speech API classes accessed by the framework via reflection.
-keep class android.speech.** { *; }