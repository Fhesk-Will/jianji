# kotlinx-serialization 混淆规则
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.jianji.app.**$$serializer { *; }
-keepclassmembers class com.jianji.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.jianji.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
