# SpringCat Addon（Minecraft 統合版アドオン）

`build_addon.py` が生成する、オリジナルの Minecraft Bedrock（統合版）アドオンです。
Mojang / Minecraft の既存アセットは一切使わず、ジオメトリ・テクスチャ・JSON をすべて
このスクリプトが自前で生成します。

## 収録コンテンツ

- **Spring Cat**（`springcat:spring_cat`）— 相棒モブ。スポーンエッグ対応、`/summon springcat:spring_cat` で召喚可能。歩き回る・プレイヤーを見る・逃げるなど基本的な挙動のみを実装したシンプルな召喚モブです。
- **Spring Treat**（`springcat:spring_treat`）— 食べると跳躍力(Jump Boost)とスピードが少しの間上がるアイテム。クリエイティブの「自然」タブから入手できます。

## ビルド方法

```bash
pip install Pillow
python3 build_addon.py
```

`out/` 以下に以下が生成されます:

- `out/BP/`, `out/RP/` — 展開済みのビヘイビア/リソースパック（動作確認・編集用）
- `out/SpringCat_BP.mcpack`, `out/SpringCat_RP.mcpack`
- `out/SpringCat.mcaddon` — 上記2つをまとめた配布用ファイル（Android アプリに同梱しているのはこれ）

## 手動インストール（アプリを使わない場合）

`out/SpringCat.mcaddon` を端末に転送してタップすると、Minecraft のインポート確認画面が
開きます（Minecraft がファイル拡張子 `.mcaddon` のハンドラを登録しているため）。
インポート後、ワールド設定の「アドオン」から Behavior Pack / Resource Pack を有効化してください。

## 既知の制限・今後の改善案

- 実機の Minecraft では未検証です（このビルド環境には Minecraft 実行環境がないため）。
  アイテムイベント（`minecraft:on_use` → `run_command`）やジオメトリの数値は Microsoft の
  Bedrock アドオン仕様に沿って書いていますが、初回インポート後は必ず実機で動作確認してください。
- Spring Cat には歩行アニメーション（脚の動き）がありません。棒立ちのまま移動します。
- 自然スポーン（`spawn_rules`）は未実装。現状はスポーンエッグ／`/summon` のみです。
- UUID (`UUIDS` 定数) は固定値です。内容を更新する場合は `ADDON_VERSION` を上げてから
  再ビルドしてください（UUID を変えると別アドオン扱いになり、既存ワールドの参照が壊れます）。
