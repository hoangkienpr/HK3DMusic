# Add project-specific ProGuard/R8 rules here.
# Keep Kotlin serialization metadata for reflection if needed.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
# Keep Media3 OSD/session pieces that may be referenced via reflection by the system.
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**
# Coil loads images/video frames; keep models it serializes.
-dontwarn coil.**
# Google Cast framework: keep reflection entry points.
-keep class com.google.android.gms.cast.framework.** { *; }
-dontwarn com.google.android.gms.cast.**
