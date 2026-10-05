# kotlinx.serialization ships its own consumer rules; keep our @Serializable models explicitly too.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.goldenpaw.**$$serializer { *; }
-keepclassmembers class com.goldenpaw.** {
    *** Companion;
}
-keepclasseswithmembers class com.goldenpaw.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Room entities / DAOs are generated and referenced reflectively by name.
-keep class com.goldenpaw.data.local.** { *; }
