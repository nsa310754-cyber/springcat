#!/usr/bin/env node
/*
 * 🔒 iOS 同梱用の game.enc を生成する。
 *
 * Android と同じ AES-256-CBC (鍵 = パスフレーズの SHA-256、出力 = IV[16] + 暗号文)。
 * game.html を更新したら必ずこれを再実行して game.enc を作り直すこと。
 *
 * 使い方 (リポジトリのルートで):
 *   node ios/tools/encrypt-game.js
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// ⚠️ Android (build.gradle の gameEncPass / MainActivity の GAME_ENC_PARTS) と一致必須
const PASS = 'bd350-' + 'ragdollp-' + 'appassets-' + 'obf-k3y';

const root = path.resolve(__dirname, '..', '..');
const srcHtml = path.join(root, 'android', 'app', 'game-src', 'game.html');
const outEnc = path.join(root, 'ios', 'BlockDestroy', 'BlockDestroy', 'game.enc');

const key = crypto.createHash('sha256').update(PASS, 'utf8').digest();
const iv = crypto.randomBytes(16);
const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
const plain = fs.readFileSync(srcHtml);
const enc = Buffer.concat([cipher.update(plain), cipher.final()]);
const out = Buffer.concat([iv, enc]);
fs.writeFileSync(outEnc, out);

console.log(`[encrypt-game] wrote ${outEnc}`);
console.log(`[encrypt-game]   plain ${plain.length} bytes -> enc ${out.length} bytes (iv16 + ciphertext)`);
