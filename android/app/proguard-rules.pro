# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class app.stride.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.stride.**$$serializer { *; }

# MapLibre uses JNI callbacks into Java classes
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
