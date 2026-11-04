# kotlinx.serialization keeps generated serializers via annotations; keep them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.farewellutopia.healthdevices.** {
    *** Companion;
}
-keepclasseswithmembers class com.farewellutopia.healthdevices.** {
    kotlinx.serialization.KSerializer serializer(...);
}
