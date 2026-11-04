# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.fgc.core.**$$serializer { *; }
-keepclassmembers class com.fgc.core.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
