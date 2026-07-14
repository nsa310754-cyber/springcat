# springcat / fixapk

古い APK を **最新の Android 端末にインストールできる形に変換する**デスクトップツールです。
AI・クラウドは一切使わず、完全に決定論的な処理だけで動きます。

## 何をするのか

古い APK が新しい端末で「アプリをインストールできません」になる主な原因は 2 つあります。

1. **`targetSdkVersion` が低すぎる**
   Android 14 (API 34) は `targetSdkVersion < 23`、Android 15 (API 35) は `< 24` の
   APK をインストール時に拒否します。
2. **署名が古い / 壊れている**
   古い v1 署名のみで、最近のダイジェスト方式に合っていないと弾かれます。

`fixapk` は次の処理を自動で行います。

1. `AndroidManifest.xml`(バイナリ AXML)の `targetSdkVersion` を **24 以上に引き上げ**
   （`targetSdkVersion` が省略されているアプリは、代わりに `minSdkVersion` を引き上げ）
2. 古い `META-INF` 署名ファイルを除去
3. **JDK 同梱の `jarsigner` で v1 署名を付け直し**
4. 署名を検証して、インストール可能な APK を出力

> `targetSdkVersion` を **30 未満**に保つことで、v1 署名だけで新端末でもインストールできます
> （v2 署名が必須になるのは target ≥ 30 のアプリのみ）。そのため **Android SDK は不要**で、
> JDK さえあれば動きます。

## 必要なもの

- **JDK 8 以上**（`keytool` と `jarsigner` を使います。多くの PC に既に入っています）
- **Python 3.9 以上**
- GUI を使う場合のみ `python3-tk`（例: `sudo apt install python3-tk`)
- （任意）Android SDK の `apksigner`：`targetSdkVersion` が 30 以上のアプリを扱いたい場合。
  `PATH` か `ANDROID_HOME` にあれば自動で検出し、v1+v2+v3 署名を行います。

## 使い方

### GUI(ドラッグ&ドロップ)

```bash
python -m fixapk --gui
```

ウィンドウに古い APK をドラッグ&ドロップ(または「APKを選ぶ…」ボタン)すると、
同じフォルダに `〇〇-fixed.apk` が出力されます。

> ドラッグ&ドロップは `tkinterdnd2`(`pip install tkinterdnd2`)が入っていると有効になります。
> 無くてもボタンからファイルを選べます。

### コマンドライン

```bash
# 1 ファイル変換
python -m fixapk old.apk

# 出力先を指定
python -m fixapk old.apk -o new.apk

# まとめて変換
python -m fixapk app1.apk app2.apk app3.apk

# 引き上げる targetSdkVersion の下限を変更(既定 24)
python -m fixapk old.apk --target-sdk 26
```

出力された `*-fixed.apk` を端末に転送してインストールしてください。

## 仕組み(コード構成)

| ファイル | 役割 |
| --- | --- |
| `fixapk/axml.py` | バイナリ AndroidManifest.xml の SDK バージョンを**サイズを変えずにその場書き換え** |
| `fixapk/apk.py` | ZIP を開き、マニフェストを差し替え、旧署名を除去して再パッケージ |
| `fixapk/signer.py` | `keytool` で自己署名鍵を自動生成し、`jarsigner`(または `apksigner`)で署名・検証 |
| `fixapk/cli.py` | コマンドラインインターフェース |
| `fixapk/gui.py` | Tkinter の GUI |

`axml.py` は整数値を同じバイト数で上書きするだけなので、aapt や apktool を必要としません。

## テスト

```bash
python3 tests/test_axml.py          # AXML パーサ/パッチャ
python3 -m tests.test_end_to_end    # 変換〜jarsigner 署名の通し
```

## 制限事項

- **署名鍵が変わります。** 再署名するので元の開発者の署名とは別物になります。
  そのため元アプリの**アップデート上書きはできません**(一度アンインストールが必要)。
  Play ストア配布などはできません。あくまで手元で入れ直すための変換です。
- `<uses-sdk>` 要素も `minSdkVersion`/`targetSdkVersion` も一切書かれていない
  特殊な APK は、その場書き換えができないためエラーになります
  (完全な aapt/apktool 再ビルドが必要)。
- `targetSdkVersion` を上げると実行時の挙動(権限まわり等)が変わることがあります。
  「インストールできる」ことを最優先にした最小限の引き上げにしています。
- 自分が権利を持つ、または再配布・改変が許可された APK にのみ使ってください。
