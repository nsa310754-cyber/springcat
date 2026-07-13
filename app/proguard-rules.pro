# BouncyCastle relies on reflection for provider/algorithm lookup.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# apksig references optional javax/apk classes that may be absent at runtime.
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn javax.**
-dontwarn java.awt.**
