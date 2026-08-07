# 🎁 プレゼントコード機能 — Realtime Database セキュリティルール

コード入力機能(「その他 → 🎁 コード入力」)と管理ページ
(`https://ragdollp.site/springcat1security/blockdestory/`)を**安全に**動かすため、
Firebase Realtime Database のルールに `promo_codes` ノードを追加する必要があります。

これはプロジェクト側の設定です。**Firebase コンソール → Realtime Database → ルール** で、
今あるルールの中に下の `"promo_codes"` ブロックを **追記** してください（既存の
`world_rankings` / `user_saves` などのルールは消さないこと）。

```json
{
  "rules": {

    "promo_codes": {
      ".read": "auth != null && auth.token.email === 'nsa310754@gmail.com'",
      "$code": {
        ".read": "auth != null",
        ".write": "auth != null && ( auth.token.email === 'nsa310754@gmail.com' || ( data.exists() && data.child('used').val() === false && newData.child('used').val() === true && newData.child('usedBy').val() === auth.uid && newData.child('reward').val() === data.child('reward').val() && newData.child('type').val() === data.child('type').val() ) )"
      }
    }

    // ← ここに既存の world_rankings / user_saves / comments など他のルールを残す
  }
}
```

## このルールが保証すること

- **発行できるのは管理者だけ**: `promo_codes` にコードを新規作成できるのは
  `nsa310754@gmail.com` でログインした人だけ(コード発行 = データ新規作成なので、
  一般ユーザーの償還ブランチ `data.exists()` を通らない)。
- **一覧を見られるのは管理者だけ**: 親 `promo_codes` の `.read` が管理者限定なので、
  一般ユーザーは全コードを列挙できない(= コードを盗み見て総当たりされにくい)。
- **一般ユーザーができるのは「償還」だけ**: 特定の7桁コードを知っている
  ログイン済みユーザーは、そのコードを1回だけ `used:false → true` にでき、
  `usedBy` を自分の uid にする以外の改ざん(報酬額の書き換え等)はできない。
- **一人様限定**: 実際の「早い者勝ちで1人だけ」は、アプリ側が
  `runTransaction` で原子的に確定する。ルールと合わせて二重取得を防ぐ。

## 補足（うまく動かない時）

管理ページは apex ドメイン `ragdollp.site`(Neocities)で動くため、環境によっては
次の許可が必要になることがあります:

1. **Firebase Authentication → Settings → 承認済みドメイン** に `ragdollp.site` を追加
   (メール/パスワードのログインが弾かれる場合)。
2. **reCAPTCHA Enterprise キー `6LcYpDUtAAAAACkkjqkUMbiVPXcik01MrwQ1uvqK` の許可ドメイン**に
   `ragdollp.site` を追加(App Check を強制していて、管理ページの書き込みが
   App Check で弾かれる場合)。

アプリ内の「コード入力」は `appassets.androidplatform.net` で動き、そこは既に
App Check が通っているため、追加設定は不要です。
