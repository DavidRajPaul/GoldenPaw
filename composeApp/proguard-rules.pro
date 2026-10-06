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
-keep class com.goldenpaw.data.remote.** { *; }
# Ktor / OkHttp optional platform classes.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Glance widget callbacks are instantiated by class name.
-keep class com.goldenpaw.widget.** { *; }
