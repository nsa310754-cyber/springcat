// RSA keygen + self-signed X.509 cert + APK Signature Scheme v2 — all WebCrypto.
import { concat, u32le, u64le, rd16, rd32, wr32 } from './engine.mjs';

// ---------- minimal DER ----------
function derLen(n) {
  if (n < 0x80) return new Uint8Array([n]);
  const bytes = [];
  let v = n;
  while (v > 0) { bytes.unshift(v & 0xff); v >>>= 8; }
  return new Uint8Array([0x80 | bytes.length, ...bytes]);
}
function tlv(tag, content) { return concat([new Uint8Array([tag]), derLen(content.length), content]); }
const SEQ = (c) => tlv(0x30, concat(c));
const SET = (c) => tlv(0x31, concat(c));
function derInt(bytes) { // bytes = big-endian magnitude
  let b = bytes;
  if (b.length === 0) b = new Uint8Array([0]);
  if (b[0] & 0x80) b = concat([new Uint8Array([0]), b]); // keep positive
  return tlv(0x02, b);
}
const derNull = () => new Uint8Array([0x05, 0x00]);
function derOid(parts) {
  const first = 40 * parts[0] + parts[1];
  const body = [first];
  for (let i = 2; i < parts.length; i++) {
    let v = parts[i]; const stack = [v & 0x7f]; v = Math.floor(v / 128);
    while (v > 0) { stack.unshift((v & 0x7f) | 0x80); v = Math.floor(v / 128); }
    body.push(...stack);
  }
  return tlv(0x06, new Uint8Array(body));
}
const derUtf8 = (s) => tlv(0x0c, new TextEncoder().encode(s));
const derBitString = (bytes) => tlv(0x03, concat([new Uint8Array([0]), bytes]));
function derUTCTime(d) {
  const p = (n) => String(n).padStart(2, '0');
  const s = p(d.getUTCFullYear() % 100) + p(d.getUTCMonth() + 1) + p(d.getUTCDate()) +
    p(d.getUTCHours()) + p(d.getUTCMinutes()) + p(d.getUTCSeconds()) + 'Z';
  return tlv(0x17, new TextEncoder().encode(s));
}

const OID_SHA256_RSA = derOid([1, 2, 840, 113549, 1, 1, 11]);
const OID_CN = derOid([2, 5, 4, 3]);
const ALG_SHA256_RSA = SEQ([OID_SHA256_RSA, derNull()]);

// ---------- keystore (RSA + self-signed cert) ----------
export async function generateKeystore(commonName) {
  const kp = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) },
    true, ['sign', 'verify']);
  const spki = new Uint8Array(await crypto.subtle.exportKey('spki', kp.publicKey));

  const serial = crypto.getRandomValues(new Uint8Array(16));
  const name = SEQ([SET([SEQ([OID_CN, derUtf8(commonName)])])]);
  const notBefore = derUTCTime(new Date(Date.UTC(2020, 0, 1)));
  const notAfter = derUTCTime(new Date(Date.UTC(2049, 11, 31, 23, 59, 59)));
  const validity = SEQ([notBefore, notAfter]);
  const version = tlv(0xa0, derInt(new Uint8Array([0x02]))); // v3
  const tbs = SEQ([version, derInt(serial), ALG_SHA256_RSA, name, validity, name, spki]);

  const tbsSig = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', kp.privateKey, tbs));
  const cert = SEQ([tbs, ALG_SHA256_RSA, derBitString(tbsSig)]);

  return { privateKey: kp.privateKey, publicKey: kp.publicKey, spki, certDer: cert };
}

// ---------- APK Signature Scheme v2 ----------
const V2_ID = 0x7109871a;
const ALGO_RSA_SHA256 = 0x0103;
const MAGIC = new TextEncoder().encode('APK Sig Block 42'); // 16 bytes

const lp = (bytes) => concat([u32le(bytes.length), bytes]);              // length-prefixed
const lpSeq = (elems) => lp(concat(elems.map(lp)));                       // length-prefixed sequence of length-prefixed

async function chunkedDigest(sections) {
  const CHUNK = 1048576;
  const digests = [];
  for (const sec of sections) {
    for (let o = 0; o < sec.length; o += CHUNK) {
      const chunk = sec.subarray(o, Math.min(o + CHUNK, sec.length));
      const pre = concat([new Uint8Array([0xa5]), u32le(chunk.length)]);
      digests.push(new Uint8Array(await crypto.subtle.digest('SHA-256', concat([pre, chunk]))));
    }
  }
  const top = concat([new Uint8Array([0x5a]), u32le(digests.length), ...digests]);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', top));
}

// unsignedApk must be a valid ZIP with entries + central dir + EOCD.
export async function signV2(unsignedApk, keystore) {
  // locate EOCD + central directory
  let eocd = -1;
  for (let i = unsignedApk.length - 22; i >= Math.max(0, unsignedApk.length - 65557); i--) {
    if (rd32(unsignedApk, i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('EOCD not found');
  const cdSize = rd32(unsignedApk, eocd + 12);
  const cdOffset = rd32(unsignedApk, eocd + 16);

  const section1 = unsignedApk.subarray(0, cdOffset);
  const centralDir = unsignedApk.subarray(cdOffset, cdOffset + cdSize);
  const eocdBytes = unsignedApk.slice(eocd); // copy (we won't mutate for digest; cdOffset already = start of block)

  const digest = await chunkedDigest([section1, centralDir, eocdBytes]);

  // signed data
  const digestsSeq = lpSeq([concat([u32le(ALGO_RSA_SHA256), lp(digest)])]);
  const certsSeq = lpSeq([keystore.certDer]);
  const attrsSeq = lpSeq([]); // none
  const signedData = concat([digestsSeq, certsSeq, attrsSeq]);

  const signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', keystore.privateKey, signedData));
  const signaturesSeq = lpSeq([concat([u32le(ALGO_RSA_SHA256), lp(signature)])]);

  const signer = concat([lp(signedData), signaturesSeq, lp(keystore.spki)]);
  const signersSeq = lpSeq([signer]);          // v2 block value

  // ID-value pair
  const pair = concat([u64le(4 + signersSeq.length), u32le(V2_ID), signersSeq]);

  // APK Signing Block = u64 size | pairs | u64 size | magic
  const blockLen = pair.length + 8 + 16;
  const signingBlock = concat([u64le(blockLen), pair, u64le(blockLen), MAGIC]);

  // rebuild EOCD with cdOffset shifted by the inserted block
  const newEocd = eocdBytes.slice();
  wr32(newEocd, 16, cdOffset + signingBlock.length);

  return concat([section1, signingBlock, centralDir, newEocd]);
}
