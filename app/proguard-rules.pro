# Keep BouncyCastle provider classes (loaded reflectively by JCE).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
