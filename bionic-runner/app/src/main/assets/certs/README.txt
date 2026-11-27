Microsoft のコード署名のルート証明書 (公開されているもの。Microsoft の PKI のページ
https://www.microsoft.com/pki/certs/ から取ったもの)。
WebView2 を取り込むときに、Authenticode の署名の証明書の連鎖を、端末の中で確かめるために使う
(Android には、コード署名用の Microsoft のルート証明書は入っていない)。
使う前に、Authenticode.java に書いた SHA-256 の指紋と照合する。

microsoft-root-2011.cer  Microsoft Root Certificate Authority 2011
  SHA-256 847df6a78497943f27fc72eb93f9a637320a02b561d0a91b09e87a7807ed7c61
microsoft-root-2010.cer  Microsoft Root Certificate Authority 2010
  SHA-256 df545bf919a2439c36983b54cdfc903dfa4f37d3996d8d84b4c31eec6f3c163e
