# Room and Hilt generate code that reflection reaches; keep their entry points.
-keep class com.john.assistant.data.database.** { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }

# Services are instantiated by the system by name.
-keep class com.john.assistant.services.** { *; }

# kotlinx.serialization keeps generated serializers via companion references.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
