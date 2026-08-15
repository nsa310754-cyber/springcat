# minifyEnabled=false のため実質未使用。将来 R8 を有効化する場合のための最小設定。
-keep class site.ragdollp.filemanager.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**
