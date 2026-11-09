# WebView の JS から呼ばれるブリッジ (addJavascriptInterface) のメソッド名を残す。
-keepclassmembers class site.ragdollp.blockdestory.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# クラッシュ報告で行番号が分かるようにする (名前は mapping.txt で元に戻せる)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
