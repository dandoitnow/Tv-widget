# kotlinx.serialization keeps its generated serializers on the class itself.
-keepclassmembers class com.example.tvwidget.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.tvwidget.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
