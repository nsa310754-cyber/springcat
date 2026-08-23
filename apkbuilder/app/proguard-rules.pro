# Bouncy Castle (JCA providers are resolved by name/reflection) and Google's apksig.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
# Our engine (defensive; loaded normally but keep to be safe).
-keep class com.apkbuilder.core.** { *; }
# Misc optional refs pulled in by apksig/BC.
-dontwarn javax.annotation.**
-dontwarn java.awt.**
