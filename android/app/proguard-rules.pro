# WebView から JS で呼ばれる可能性に備え、公開メンバは温存する。
# (現状 @JavascriptInterface は未使用だが将来の拡張向け)
-keepclassmembers class site.ragdollp.blockdestory.** {
    @android.webkit.JavascriptInterface <methods>;
}
