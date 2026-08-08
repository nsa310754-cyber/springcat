# WebView + JS ブリッジは無いが、将来 addJavascriptInterface を使う場合に備えたテンプレ。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
