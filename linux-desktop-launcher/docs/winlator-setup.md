# Winlator でスマホ単体 Windows ゲームを動かす(手順)

> 結論:スマホ単体で Windows ゲームを実際に動かすなら **Winlator** が確実。
> 自作アプリに同じGPU/Xサーバ中核をゼロから積むのは非現実的(Winlator はそれを完成させた
> オープンソースアプリ)。本書は Winlator の導入手順。

## 1. 前提(端末要件)
- **GPU**: Qualcomm **Adreno** が最も相性が良い(**Turnip** ドライバが使える)。
  Mali は VirGL/Vortek 経由で動くが遅い/相性差あり。
- **Android 12 以降**、RAM 6〜8GB 以上推奨、空き容量 数GB〜。
- Vulkan 対応必須。

## 2. 入手(フォーク選択)
- **まず公式(Winlator 11 系, 2026)から**。Adreno + Turnip で十分動く。
  - 公式: <https://github.com/winebox64/winlator>
- 物足りない/特定GPU向け拡張が欲しい時だけフォーク:
  - **Winlator CMOD**(高性能 Snapdragon 向け・ドライバ追加・端末spoof): <https://github.com/Stredohori/Winlator-CMOD>
  - **Winlator Bionic**(Bionicベースで高速化): <https://github.com/Succubussix/winlator-bionic-glibc>
- APK を入れて「不明なアプリ」を許可してインストール。初回起動で **imagefs(rootfs)を展開**。

## 3. コンテナ作成と設定(ここが肝)
1. Winlator を開く → **Containers → 新規コンテナ作成**
2. **Graphics Driver**: Adreno なら **Turnip**(必要なら自分の Adreno 世代向け Turnip パッケージを
   ドライバ設定でインポート)
3. **DX Wrapper**:
   - DirectX 8/9/10/11 → **DXVK**
   - DirectX 12 → **VKD3D-Proton**
4. **Box64 preset**: Performance 寄りに(動的再コンパイラ設定)
5. 画面解像度・RAM・CPU コア等を端末に合わせて調整

## 4. ゲームの導入と起動
1. ゲーム本体(インストーラ or ポータブル版)を端末に用意
2. Winlator のコンテナ内 **drive_c**(または共有フォルダをマウント)へコピー
3. PC と同じ感覚で **setup.exe を実行してインストール**(or 直接 game.exe)
4. **ショートカット作成**: インストールした game.exe を指すショートカットをコンテナに追加
5. ショートカットをタップ → Winlator 内蔵 X サーバ上でゲームが起動

## 5. うまく動かない時
- カクつく/起動しない → **別の Turnip バージョン**を試す、DXVK バージョン変更
- DX12 ゲーム → VKD3D-Proton を有効化
- 重い → 解像度を下げる、Box64 を Performance、ESYNC 有効
- Mali 端末 → VirGL/Vortek を選択(Adreno より制約大)

## 6. 比較(参考)
- **Winlator**: 汎用・無料・フォーク豊富(推奨の出発点)
- **GameNative / GameHub / Mobox**: 類似の代替。端末・ゲームにより相性が違う

## 参考
- Winlator 公式: <https://github.com/winebox64/winlator>
- Box64(x86→ARM 変換): <https://github.com/ptitSeb/box64>
- Turnip = Mesa の Adreno 向け Vulkan ドライバ
- DXVK = DirectX→Vulkan、VKD3D-Proton = DirectX12→Vulkan
