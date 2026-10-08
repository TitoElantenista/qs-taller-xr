-keepattributes *Annotation*

# Documents read and written as JSON (kotlinx.serialization).
-keep class com.qs.tallerxr.data.** { *; }
-keep class com.qs.tallerxr.projects.** { *; }
-keep class com.qs.tallerxr.importer.IfcPartsDocument { *; }
-keep class com.qs.tallerxr.importer.IfcAssemblyInfo { *; }
-keep class com.qs.tallerxr.importer.PreparedStamp { *; }

# The IFC reader calls back into Kotlin by method name from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
