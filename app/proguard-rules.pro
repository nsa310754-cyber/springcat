# apksig reflects over signature scheme implementations and pulls in a few
# desktop-only classes that are never reached on Android.
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# BouncyCastle registers algorithms by reflection from class names.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
