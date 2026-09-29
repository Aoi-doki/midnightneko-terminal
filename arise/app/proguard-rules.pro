# MediaPipe GenAI talks to its native runner through JNI and reflection-built protos.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn org.checkerframework.**

# ML Kit ships its own consumer rules; keep the pose classes the analyzer touches by name.
-keep class com.google.mlkit.vision.pose.** { *; }

# kotlinx.serialization: keep generated serializers for our @Serializable DTOs.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class dev.aoidoki.arise.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.aoidoki.arise.**$$serializer { *; }
