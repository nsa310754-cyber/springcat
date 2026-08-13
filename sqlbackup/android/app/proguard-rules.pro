# minifyEnabled は false のため通常は使われないが、将来 R8 を有効にした場合に備えて
# JDBC ドライバの Class.forName によるリフレクションロードを保護しておく。
-keep class net.sourceforge.jtds.jdbc.Driver { *; }
-keep class net.sourceforge.jtds.** { *; }
