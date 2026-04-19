# Add project-specific ProGuard rules here.
# Keep kotlinx.serialization generated classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Hilt generated code.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Retrofit / OkHttp defaults are bundled with consumer rules.
