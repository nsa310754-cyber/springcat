# SpringCat Addon（Minecraft 統合版アドオン）

`build_addon.py` が生成する、オリジナルの Minecraft Bedrock（統合版）アドオンです。
Mojang / Minecraft の既存アセットは一切使わず、ジオメトリ・テクスチャ・JSON をすべて
このスクリプトが自前で生成します。

## 収録コンテンツ

- **Spring Cat**（`springcat:spring_cat`）— 相棒モブ。スポーンエッグ対応、`/summon springcat:spring_cat` で召喚可能。歩き回る・プレイヤーを見る・逃げるなど基本的な挙動のみを実装したシンプルな召喚モブです。
- **Spring Treat**（`springcat:spring_treat`）— 食べると跳躍力(Jump Boost)とスピードが少しの間上がるアイテム。クリエイティブの「自然」タブから入手できます。
- **Spring Turbo Block**（`springcat:turbo_block`）— レール(通常/パワード/アクティベーターいずれでも可)の**真下**に敷くと、その上を通過するトロッコを毎tick加速し続けるブロック。詳細は下記「加速の仕組み」を参照。クリエイティブの「建築」タブ、または `minecraft:gold_block` + `springcat:spring_treat` のシェイプレスクラフトで入手できます。
- **チェーンメイル一式**(ヘルメット/チェストプレート/レギンス/ブーツ)— バニラの `minecraft:chainmail_*` は本来クラフト不可(村人取引・戦利品限定)ですが、本アドオンはバニラの `minecraft:chain`(鎖ブロック)を素材にした本物のクラフトレシピを追加します。鉄防具と同じ配置パターンで、鉄インゴットの代わりに chain を使います。
- **チェーン道具一式**(`springcat:chain_pickaxe` / `chain_axe` / `chain_shovel` / `chain_hoe` / `chain_sword`)— 素材は同じく `minecraft:chain`。ステータスは Minecraft 公式の銅(Copper)ツールと同等になるよう調整しています。詳細は下記「チェーン道具のステータス」を参照。

## 加速の仕組み(調査結果と実装方針)

Minecraft 統合版のパワードレールは、トロッコを**最大 8 block/s(≒0.4 block/tick)まで**加速する
仕様で、この上限はエンジン組み込みのレール加速ロジックにハードコードされており、ブロックの
コンポーネント(データ駆動 JSON)側からは変更できません。Bedrock 向けにも「もっと速く走る
トロッコ」を実現しているアドオンは既に複数公開されており(例: CurseForge の *Better Faster
Minecart*、*Faster Minecarts!* など)、いずれも Script API 経由でトロッコに直接速度を与える
方式を取っています。

本アドオンでは `scripts/main.js`(`@minecraft/server` の Script API)で以下を毎tick行っています:

1. 全ディメンションのトロッコ系エンティティ(`minecraft:minecart` ほか計5種)を走査
2. `Entity.getVelocity()` で現在の水平速度を取得(止まっているカートは対象外)
3. `Dimension.getBlockBelow()` でレールの真下のブロックを取得(レール自体は非ソリッドなので
   自動的に透過され、支えているブロックが返る)
4. それが `springcat:turbo_block` なら、`Entity.applyImpulse()` で進行方向へ速度を加算

`applyImpulse` はバニラのレール加速ロジックとは別経路で速度を直接加算するため、8 block/s の
キャップを受けません。**加速量に上限は設けていません**(ユーザーの要望どおり)。

### 既知のトレードオフ

上限を設けていない分、長い区間を走らせ続けると現実的な物理挙動が破綻する可能性があります:

- 非常に高速になると、1tickでの移動量がブロック1個分を超え、当たり判定をすり抜けて脱線・
  落下することがあります(一般的なゲームエンジンの当たり判定の限界によるもの)。
- 減速したい場合は、`turbo_block` を敷いていない区間を挟んでください(バニラの摩擦で自然に
  減速します)。
- `BOOST_PER_TICK`(`scripts/main.js` 内、既定 `0.12`)を下げれば、緩やかな加速に調整できます。

## チェーン防具・チェーン道具について(調査結果と実装方針)

### チェーンメイルのレシピ

バニラの Bedrock ではチェーンメイル一式はクラフト不可能で、村人(鍛冶屋)取引や
戦利品でのみ入手できます。本アドオンはネザーアップデートで追加された既存ブロック
`minecraft:chain`(鎖)を材料に見立て、鉄防具と同じ配置で以下のレシピを追加します:

| 部位 | 配置 | 消費する chain |
|---|---|---|
| ヘルメット | `XXX` / `X_X` | 5 |
| チェストプレート | `X_X` / `XXX` / `XXX` | 8 |
| レギンス | `XXX` / `X_X` / `X_X` | 7 |
| ブーツ | `X_X` / `X_X` | 4 |

(`X` = `minecraft:chain`。結果は素の `minecraft:chainmail_*` なので、既存の
防具強化・エンチャント・見た目はすべてバニラのままです。)

### チェーン道具のステータス(銅ツール相当)

Minecraft Wiki(Copper Tools / Tiers ページ)で確認した Bedrock 版の銅ツール実測値に
合わせています:

| 項目 | 値 | 出典 |
|---|---|---|
| 耐久値 | 191 | Copper Pickaxe/Sword/Axe/Shovel/Hoe (Bedrock) |
| 採掘効率 (efficiency) | 5 | pickaxe/axe/shovel 共通 |
| エンチャント率 | 13 | 全ツール共通 |
| 採掘レベル | ストーンと同格(鉄鉱石・銅鉱石・ラピスラズリを採掘可。金/ダイヤ/エメラルド鉱石・ネザライト・黒曜石は不可) | Tiers ページ |
| 攻撃力 (Bedrock) | pickaxe 4 / axe 5 / shovel 4 / hoe 4 / sword 6 | 各アイテムページ |

修理は `minecraft:chain` で(耐久最大値の25%回復)。クラフトも同じく `minecraft:chain`
+ `minecraft:stick` で、パターンはバニラのツール類と同じ配置(例: つるはしは
`XXX` / `_#_` / `_#_`、剣は `X` / `X` / `#` など)です。

### 実装上の注意(既知の不確実性)

- 採掘の**速度**(`minecraft:digger` の `destroy_speeds`)は上記の値でほぼ確実に再現
  できますが、鉱石が「正しいツールで採掘したときだけドロップする」という**採掘レベルの
  可否判定**は、Bedrock ではエンジン内部のロジックに強く依存しており、コミュニティの
  技術文書(bedrock.dev)を根拠に `minecraft:tags` へ `minecraft:stone_tier` 等のタグを
  付与する実装にしています。この部分は実機での動作未検証です。もし銅/鉄鉱石が正しく
  ドロップしない場合は、`BP/items/chain_*.json` の `minecraft:tags` を調整してください。
- `minecraft:damage` は「素手ダメージ(1)に加算される値」という仕様のため、上表の
  攻撃力から `-1` した値を `minecraft:damage` に設定しています(例: 剣は攻撃力6 →
  `minecraft:damage: 5`)。

## ビルド方法

```bash
pip install Pillow
python3 build_addon.py
```

`out/` 以下に以下が生成されます:

- `out/BP/`, `out/RP/` — 展開済みのビヘイビア/リソースパック（動作確認・編集用）
- `out/SpringCat_BP.mcpack`, `out/SpringCat_RP.mcpack`
- `out/SpringCat.mcaddon` — 上記2つをまとめた配布用ファイル（Android アプリに同梱しているのはこれ）
- `out/SpringCat-Addon-vX.Y.Z.zip` — ストア/配布サイト提出用の zip。`SpringCat.mcaddon` +
  掲載用アイコン(`icon.png`) + 説明文(`description.txt`)を同梱(`dist/` にも配置済み)。

## 手動インストール（アプリを使わない場合）

`out/SpringCat.mcaddon` を端末に転送してタップすると、Minecraft のインポート確認画面が
開きます（Minecraft がファイル拡張子 `.mcaddon` のハンドラを登録しているため）。
インポート後、ワールド設定の「アドオン」から Behavior Pack / Resource Pack を有効化してください。

## 既知の制限・今後の改善案

- 実機の Minecraft では未検証です（このビルド環境には Minecraft 実行環境がないため）。
  アイテムイベント（`minecraft:on_use` → `run_command`）やジオメトリの数値、Script API の
  挙動は Microsoft の公式リファレンス（Manifest / Script API ドキュメント）に沿って書いて
  いますが、初回インポート後は必ず実機で動作確認してください。
- Spring Cat には歩行アニメーション（脚の動き）がありません。棒立ちのまま移動します。
- 自然スポーン（`spawn_rules`）は未実装。現状はスポーンエッグ／`/summon` のみです。
- Spring Turbo Block はワールド側で「ベータ API」等の実験的機能の有効化が必要になる場合が
  あります(Minecraft のバージョンによって Script API の扱いが異なるため)。うまく動かない
  場合はワールド設定の実験的機能を確認してください。
- UUID (`UUIDS` 定数) は固定値です。内容を更新する場合は `ADDON_VERSION` を上げてから
  再ビルドしてください（UUID を変えると別アドオン扱いになり、既存ワールドの参照が壊れます）。
