# ProGuard rules for Multi-Format Document Converter

# Keep data model classes
-keep class com.converter.core.model.** { *; }

# Keep error classes for proper exception handling
-keep class com.converter.core.error.** { *; }

# PdfBox-Android rules
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.fontbox.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
