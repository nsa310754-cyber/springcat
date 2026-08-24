// APK Builder — browser engine. Pure Web APIs (WebCrypto, CompressionStream).
// Turns a bundled template APK into a new, signed (APK Signature Scheme v2) APK
// entirely client-side. Ported from the Kotlin :core module.

// ---------- little-endian byte helpers ----------
const u8 = (a) => (a instanceof Uint8Array ? a : new Uint8Array(a));
function concat(arrays) {
  let n = 0; for (const a of arrays) n += a.length;
  const out = new Uint8Array(n); let o = 0;
  for (const a of arrays) { out.set(a, o); o += a.length; }
  return out;
}
function u16le(n) { return new Uint8Array([n & 0xff, (n >>> 8) & 0xff]); }
function u32le(n) { return new Uint8Array([n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff]); }
function u64le(n) { // n < 2^53
  const lo = n >>> 0, hi = Math.floor(n / 0x100000000) >>> 0;
  return concat([u32le(lo), u32le(hi)]);
}
function rd16(b, o) { return b[o] | (b[o + 1] << 8); }
function rd32(b, o) { return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0; }
function wr32(b, o, v) { b[o] = v & 0xff; b[o + 1] = (v >>> 8) & 0xff; b[o + 2] = (v >>> 16) & 0xff; b[o + 3] = (v >>> 24) & 0xff; }

// ---------- deflate / inflate (raw) ----------
async function inflateRaw(bytes) {
  const ds = new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')));
  return new Uint8Array(await ds.arrayBuffer());
}
async function deflateRaw(bytes) {
  const cs = new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw')));
  return new Uint8Array(await cs.arrayBuffer());
}

// ---------- CRC32 ----------
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[i] = c >>> 0; }
  return t;
})();
function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

// ---------- ZIP read ----------
// Returns [{name, method, crc, compSize, uncompSize, rawBytes()}]
function zipRead(src) {
  // find EOCD
  let eocd = -1;
  for (let i = src.length - 22; i >= Math.max(0, src.length - 65557); i--) {
    if (rd32(src, i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('EOCD not found (not a zip/apk)');
  const count = rd16(src, eocd + 10);
  let p = rd32(src, eocd + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (rd32(src, p) !== 0x02014b50) throw new Error('bad central dir at ' + p);
    const method = rd16(src, p + 10);
    const crc = rd32(src, p + 16);
    const compSize = rd32(src, p + 20);
    const uncompSize = rd32(src, p + 24);
    const nameLen = rd16(src, p + 28);
    const extraLen = rd16(src, p + 30);
    const commentLen = rd16(src, p + 32);
    const localOff = rd32(src, p + 42);
    const name = new TextDecoder().decode(src.subarray(p + 46, p + 46 + nameLen));
    entries.push({
      name, method, crc, compSize, uncompSize,
      rawBytes() {
        const lp = localOff;
        if (rd32(src, lp) !== 0x04034b50) throw new Error('bad local header ' + name);
        const nl = rd16(src, lp + 26), el = rd16(src, lp + 28);
        const start = lp + 30 + nl + el;
        return src.subarray(start, start + compSize);
      },
    });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

// ---------- ZIP write (4-byte align STORED data) ----------
// entries: [{name, method(0|8), crc, compSize, uncompSize, data}]
function zipWrite(entries) {
  const parts = [];
  const central = [];
  let offset = 0;
  const DOS_TIME = 0, DOS_DATE = 0x21;
  for (const e of entries) {
    const nameBytes = new TextEncoder().encode(e.name);
    const localOffset = offset;
    let extra = new Uint8Array(0);
    if (e.method === 0) {
      const base = localOffset + 30 + nameBytes.length;
      const rem = base % 4;
      if (rem !== 0) {
        let need = 4 - rem; if (need < 4) need += 4; // align padding, min 4 for a valid extra field
        extra = new Uint8Array(need);
        const sub = need - 4;
        extra[0] = 0; extra[1] = 0; extra[2] = sub & 0xff; extra[3] = (sub >>> 8) & 0xff;
      }
    }
    const lfh = concat([
      u32le(0x04034b50), u16le(20), u16le(0), u16le(e.method), u16le(DOS_TIME), u16le(DOS_DATE),
      u32le(e.crc), u32le(e.compSize), u32le(e.uncompSize), u16le(nameBytes.length), u16le(extra.length),
      nameBytes, extra, e.data,
    ]);
    parts.push(lfh);
    offset += lfh.length;
    central.push({ e, nameBytes, localOffset });
  }
  const cdStart = offset;
  for (const c of central) {
    const cd = concat([
      u32le(0x02014b50), u16le(20), u16le(20), u16le(0), u16le(c.e.method), u16le(DOS_TIME), u16le(DOS_DATE),
      u32le(c.e.crc), u32le(c.e.compSize), u32le(c.e.uncompSize), u16le(c.nameBytes.length),
      u16le(0), u16le(0), u16le(0), u16le(0), u32le(0), u32le(c.localOffset), c.nameBytes,
    ]);
    parts.push(cd); offset += cd.length;
  }
  const cdSize = offset - cdStart;
  const eocd = concat([
    u32le(0x06054b50), u16le(0), u16le(0), u16le(central.length), u16le(central.length),
    u32le(cdSize), u32le(cdStart), u16le(0),
  ]);
  parts.push(eocd);
  return concat(parts);
}

export { concat, u8, u16le, u32le, u64le, rd16, rd32, wr32, inflateRaw, deflateRaw, crc32, zipRead, zipWrite };
