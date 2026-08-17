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

| 部位 | 配置(空白マスは半角スペース) | 消費する chain |
|---|---|---|
| ヘルメット | `XXX` / `X X` | 5 |
| チェストプレート | `X X` / `XXX` / `XXX` | 8 |
| レギンス | `XXX` / `X X` / `X X` | 7 |
| ブーツ | `X X` / `X X` | 4 |

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
`XXX` / ` # ` / ` # `、剣は `X` / `X` / `#` など、空白マスは半角スペース)です。

### 実装上の注意(既知の不確実性)

- 採掘の**速度**(`minecraft:digger` の `destroy_speeds`)は上記の値でほぼ確実に再現
  できますが、鉱石が「正しいツールで採掘したときだけドロップする」という**採掘レベルの
  可否判定**は、Bedrock ではエンジン内部のロジックに強く依存しており、コミュニティの
  技術文書(bedrock.dev)を根拠に `minecraft:tags` へ `minecraft:stone_tier` 等のタグを
  付与する実装にしています。この部分は実機での動作未検証です。もし銅/鉄鉱石が正しく
  ドロップしない場合は、`BP/items/chain_*.json` の `minecraft:tags` を調整してください。

## v1.2.1 での修正(実機テストで発覚した不具合)

v1.2.0 をユーザーが実機の Minecraft へインストールしたところ、「クラフトできない」
「テクスチャが正しく表示されない」という不具合が発生しました。原因を Mojang 公式の
実サンプルファイル(`copper_spear.json` / `apple.json` / `golden_apple.json` /
`iron_pickaxe.json` / `stone_axe.json` を GitHub の
[Mojang/bedrock-samples](https://github.com/Mojang/bedrock-samples) 等から直接取得して
比較)で特定し、以下をすべて修正しました:

| 問題 | 誤り(v1.2.0) | 正しい形式(v1.2.1、公式サンプルで確認済み) | 影響 |
|---|---|---|---|
| シェイプドレシピの空きマス | `"_"` | 半角スペース `" "` | 追加した**全レシピ(9個)がクラフト不可**だった |
| シェイプレスレシピのフィールド名 | `"input"` / `"output"` | `"ingredients"` / `"result"` | Spring Turbo Block のレシピもクラフト不可だった |
| `minecraft:icon` | `{"texture": name}` | `{"textures": {"default": name}}` | アイテムのテクスチャが正しく表示されない原因 |
| `minecraft:hand_equipped` | `true`(素の値) | `{"value": true}` | チェーン道具全5種 |
| `minecraft:damage` | `5`(素の値) | `{"value": 5}` | チェーン道具全5種 |
| `minecraft:use_animation` | `"eat"`(素の値) | `{"value": "eat"}` | Spring Treat |
| Spring Treat の効果付与 | `on_use` + `events` + `run_command`(未検証の組み合わせ) | `minecraft:food.effects`(公式の食べ物効果付与機構) | Spring Treat |

再発防止のため、`ITEM_FORMAT_VERSION` / `RECIPE_FORMAT_VERSION` を Mojang 公式サンプルで
確認できた `1.21.100` に統一し、`min_engine_version` もそれに合わせて引き上げた……のですが、
これが**新たな不具合**を生みました(下記 v1.2.2 参照)。

## v1.2.2 での修正(v1.2.1 のバージョン指定が高すぎた)

v1.2.1 を実機に入れても「一切何も変わらない」という報告がありました。原因は
v1.2.1 で `min_engine_version` / 各ファイルの `format_version` を `1.21.100` に
引き上げたことです。この数値は `Mojang/bedrock-samples` リポジトリの **`main`
ブランチ**(`preview` チャンネル向けの最新開発内容を含む)から拾った値で、
実際に Microsoft の公式ドキュメントページが参照しているサンプルファイルの URL を
よく見ると `github.com/Mojang/bedrock-samples/tree/preview/...` と、**preview
ブランチ**を指していました。つまり `1.21.100` はプレビュー版 Minecraft でしか
存在しない可能性がある値で、製品版(正式版)の Minecraft ではこの
`min_engine_version` を満たせず、**アドオン自体がロード・有効化を拒否され、
何も変わっていないように見えていた**と考えられます。

`min_engine_version` と各ファイルの `format_version` を、長期間安定して使われてきた
`1.21.0` まで引き下げました(v1.2.1 で修正したコンポーネントの形式自体は Microsoft の
公式ドキュメントで「format_version 1.20.0〜1.20.50 以降で有効」と明記されているため、
`1.21.0` でも問題なく機能するはずです)。バージョン番号を実機で確認せずに「一番新しく
確認できた値」へ寄せてしまったのが今回の判断ミスです。

## v1.2.3 での修正(実際のゲームエンジンで検証)

v1.2.2 でも「クラフトできない」「テクスチャが違う」「耐久値が減らない」という報告が
続いたため、今回は推測や公式サンプルの引き写しをやめ、**Mojang 公式配布の Bedrock
Dedicated Server(バージョン 1.26.44.3、Minecraft のバージョン表記自体が `1.21.x` から
`1.26.x` の年.月形式に変わっていたことも判明)を実際にこの開発環境にダウンロード・
起動し、本アドオンを読み込ませて、サーバーのコンテンツログ(`[WARN]`/`[ERROR]`)を
直接確認しながら 1 つずつ修正**しました。これは「動くはず」の推測ではなく、実際の
エンジンが吐いたエラーメッセージそのものに基づく修正です。

判明した内容:

| ログに出ていたメッセージ | 原因 | 修正 |
|---|---|---|
| `[Recipes] ... 1.20+ Recipes require unlock data` | レシピの `format_version` が `1.20` 以降だと `"unlock"` フィールドが**必須**で、無いとレシピ自体がロード拒否される。**追加した全レシピ(10個)がこれで無効化されていた** = クラフトできない の直接原因 | レシピの `format_version` を、バニラの実レシピファイルが今も使い続けている `"1.12"` に統一(`"1.12"` は unlock 必須化の対象外) |
| `[Item] ... minecraft:icon ...` は書いても実際は無警告で通ってしまうが、**バニラの実アイテムファイルは1つも `minecraft:icon` を書いていない**ことが判明 | `item_texture.json` 側のキー名がアイテム識別子の短縮名(名前空間を除いた部分)と一致していれば、`minecraft:icon` を書かなくても自動解決される。こちらで試した書き方(`{"texture": ...}` や `{"textures": {"default": ...}}`)は、少なくとも一部の状況で意図通りに解決されていなかった可能性がある = テクスチャが違う の一因 | `minecraft:icon` コンポーネントを完全に削除し、命名規則(識別子の短縮名 = テクスチャキー名)に任せる方式に変更 |
| `[Item] ... description -> category: ... not present in the Schema` | `description.category` はスキーマに存在しないフィールドで、書いても黙って無視される | `description.menu_category.category` に修正(ブロック側は元から `menu_category` を使っていて無警告だった) |
| `[Item] ... minecraft:food -> effects: ... not present in the Schema` | `format_version "1.21.0"` では `minecraft:food.effects` がスキーマに存在せず無視される(バニラの `golden_apple.json` は `format_version "1.10"` を使うことでこの機構を有効にしている) | Spring Treat のみ `format_version` を `"1.16.0"` に下げて `food.effects` を有効化(チェーン道具側は `food` を使わないので `1.21.0` のまま) |

修正後、同じ Bedrock Dedicated Server でこのアドオンを再度読み込ませたところ、
**`springcat:` 関連の警告・エラーは 0 件**になりました。

### 耐久値が減らない件について

上記の検証は「パックが正しくロードされるか」(=起動時のスキーマ検証)を確認できる
ものですが、この開発環境はネットワーク制約(IPv6 が使えない、特権が制限されている
コンテナのため)によりサーバーを実際に起動してクライアントを接続することまでは
できませんでした。そのため「耐久値が減る/減らない」という実プレイ挙動そのものは
今回も直接確認できていません。

ただし、`minecraft:durability` / `minecraft:digger` / `minecraft:damage` の各
コンポーネントはコンテンツログ上は警告なしで正しく読み込まれています。**耐久値が
減らない最も典型的な原因は「クリエイティブモードでプレイしている」ことです
(バニラ仕様として、クリエイティブモードのツールは常に耐久値が減りません)**。
サバイバルモードで再度ご確認いただけますでしょうか。もしサバイバルモードでも
減らない場合は、実機のログ(Minecraft の設定にある「コンテンツログ」機能で
確認できます)を教えていただけると、より正確に切り分けできます。

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

- このビルド環境には Minecraft 実行環境が無いため、依然としてこのリポジトリ内では
  実機テストができません。v1.2.1 では item/recipe コンポーネントの形式を Mojang 公式の
  実ファイル(bedrock-samples)と直接突き合わせて修正済みですが、それでも変更後は必ず
  実機でインポート・クラフト・使用感を確認してください。
- エンティティのジオメトリ数値や client_entity の形式は、公式実ファイルでの裏取りまでは
  行っていません(長年ほぼ変わっていない安定した形式のため相対的にリスクは低いです)。
- Spring Cat には歩行アニメーション（脚の動き）がありません。棒立ちのまま移動します。
- 自然スポーン（`spawn_rules`）は未実装。現状はスポーンエッグ／`/summon` のみです。
- Spring Turbo Block はワールド側で「ベータ API」等の実験的機能の有効化が必要になる場合が
  あります(Minecraft のバージョンによって Script API の扱いが異なるため)。うまく動かない
  場合はワールド設定の実験的機能を確認してください。
- UUID (`UUIDS` 定数) は固定値です。内容を更新する場合は `ADDON_VERSION` を上げてから
  再ビルドしてください（UUID を変えると別アドオン扱いになり、既存ワールドの参照が壊れます）。
