# ML Kit & CameraX membawa consumer rules sendiri; aturan di bawah hanya pengaman tambahan.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Stack trace crash tetap terbaca (petakan dengan mapping.txt dari build release)
-keepattributes SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile

# TensorFlow Lite (model sendiri)
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
