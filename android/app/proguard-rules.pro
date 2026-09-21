# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class com.slackoff.app.model.** { *; }
-keep class kotlinx.serialization.** { *; }