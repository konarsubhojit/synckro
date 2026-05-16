# Add project-specific ProGuard rules here.
# Keep kotlinx.serialization generated classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Hilt generated code.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Optional annotations / APIs referenced by transitive libraries.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.microsoft.device.display.DisplayMask

# Retrofit / OkHttp defaults are bundled with consumer rules.
