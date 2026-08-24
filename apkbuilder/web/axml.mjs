// Binary AndroidManifest.xml (AXML) editor — JS port of the Kotlin AxmlDocument.
// Appends strings only (never renumbers), so existing indices stay valid.
import { concat, u16le, u32le, rd16, rd32, wr32 } from './engine.mjs';

const CHUNK_STRING_POOL = 0x0001;
const CHUNK_RES_MAP = 0x0180;
const CHUNK_START_ELEMENT = 0x0102;
const CHUNK_END_ELEMENT = 0x0103;
const TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10;

function rdS32(b, o) { return rd32(b, o) | 0; }

export class Axml {
  constructor(strings, resMapRaw, nodeStream) {
    this.strings = strings;            // array of JS strings
    this.resMapRaw = resMapRaw;        // Uint8Array
    this.node = nodeStream;            // Uint8Array (mutable via replace)
    this.elements = [];
    this._reindex();
  }

  static parse(bytes) {
    const rootType = rd16(bytes, 0);
    if (rootType !== 0x0003) throw new Error('not a binary AndroidManifest.xml');
    const rootHeader = rd16(bytes, 2);
    const rootSize = rd32(bytes, 4);
    let off = rootHeader;
    let strings = null, resMapRaw = new Uint8Array(0), nodeStart = -1;
    while (off < rootSize) {
      const ctype = rd16(bytes, off);
      const csize = rd32(bytes, off + 4);
      if (ctype === CHUNK_STRING_POOL) strings = parseStringPool(bytes, off);
      else if (ctype === CHUNK_RES_MAP) resMapRaw = bytes.slice(off, off + csize);
      else if (nodeStart === -1) nodeStart = off;
      off += csize;
    }
    if (!strings) throw new Error('no string pool');
    if (nodeStart < 0) throw new Error('no node chunks');
    return new Axml(strings, resMapRaw, bytes.slice(nodeStart, rootSize));
  }

  _reindex() {
    this.elements = [];
    let off = 0;
    const b = this.node;
    while (off < b.length) {
      const ctype = rd16(b, off);
      const csize = rd32(b, off + 4);
      if (ctype === CHUNK_START_ELEMENT) {
        const body = off + 16;
        const nameIdx = rdS32(b, body + 4);
        const attrStart = rd16(b, body + 8);
        const attrSize = rd16(b, body + 10);
        const attrCount = rd16(b, body + 12);
        const ns = rdS32(b, body);
        this.elements.push({ name: this.strings[nameIdx], body, chunkStart: off, attrCount, attrStart, attrSize, nsIndex: ns });
      }
      off += csize;
    }
  }

  _addString(v) {
    const i = this.strings.indexOf(v);
    if (i >= 0) return i;
    this.strings.push(v);
    return this.strings.length - 1;
  }

  _findAttr(elName, attrName) {
    const el = this.elements.find((e) => e.name === elName);
    if (!el) throw new Error('no <' + elName + '>');
    let aoff = el.body + el.attrStart;
    for (let i = 0; i < el.attrCount; i++) {
      const aName = rdS32(this.node, aoff + 4);
      if (aName >= 0 && this.strings[aName] === attrName) return { el, aoff };
      aoff += el.attrSize;
    }
    throw new Error("no '" + attrName + "' on <" + elName + '>');
  }

  _findAnyAttrByName(attrName) {
    for (const el of this.elements) {
      let aoff = el.body + el.attrStart;
      for (let i = 0; i < el.attrCount; i++) {
        const aNs = rdS32(this.node, aoff);
        const aName = rdS32(this.node, aoff + 4);
        if (aName >= 0 && this.strings[aName] === attrName) return { nsIndex: aNs, nameIndex: aName };
        aoff += el.attrSize;
      }
    }
    return null;
  }

  setStringAttr(elName, attrName, value) {
    const { aoff } = this._findAttr(elName, attrName);
    const idx = this._addString(value);
    wr32(this.node, aoff + 8, idx);
    this.node[aoff + 12] = 8; this.node[aoff + 13] = 0;
    this.node[aoff + 14] = 0; this.node[aoff + 15] = TYPE_STRING;
    wr32(this.node, aoff + 16, idx);
  }

  setIntAttr(elName, attrName, value) {
    const { aoff } = this._findAttr(elName, attrName);
    wr32(this.node, aoff + 8, 0xffffffff);
    this.node[aoff + 12] = 8; this.node[aoff + 13] = 0;
    this.node[aoff + 14] = 0; this.node[aoff + 15] = TYPE_INT_DEC;
    wr32(this.node, aoff + 16, value >>> 0);
  }

  setApplicationLabel(v) { this.setStringAttr('application', 'label', v); }
  setPackage(v) { this.setStringAttr('manifest', 'package', v); }
  setVersionName(v) { this.setStringAttr('manifest', 'versionName', v); }
  setVersionCode(v) { this.setIntAttr('manifest', 'versionCode', v); }

  addUsesPermission(perm) {
    const nameAttr = this._findAnyAttrByName('name');
    if (!nameAttr) throw new Error('no name attr to model');
    const app = this.elements.find((e) => e.name === 'application');
    if (!app) throw new Error('no <application>');
    const tagIdx = this._addString('uses-permission');
    const valueIdx = this._addString(perm);
    const chunk = buildLeaf(tagIdx, nameAttr.nsIndex, nameAttr.nameIndex, valueIdx);
    this.node = spliceInsert(this.node, app.chunkStart, chunk);
    this._reindex();
  }

  toByteArray() {
    const pool = buildStringPool(this.strings);
    const total = 8 + pool.length + this.resMapRaw.length + this.node.length;
    return concat([u16le(0x0003), u16le(8), u32le(total), pool, this.resMapRaw, this.node]);
  }
}

function buildLeaf(tagIdx, attrNs, attrName, valueIdx) {
  // START_ELEMENT
  const startSize = 8 + 8 + 20 + 20;
  const start = concat([
    u16le(CHUNK_START_ELEMENT), u16le(0x10), u32le(startSize),
    u32le(0), u32le(0xffffffff), u32le(0xffffffff), u32le(tagIdx),
    u16le(20), u16le(20), u16le(1), u16le(0), u16le(0), u16le(0),
    // attribute ns:name = valueString
    u32le(attrNs >>> 0), u32le(attrName >>> 0), u32le(valueIdx),
    u16le(8), new Uint8Array([0, TYPE_STRING]), u32le(valueIdx),
  ]);
  const endSize = 8 + 8 + 8;
  const end = concat([
    u16le(CHUNK_END_ELEMENT), u16le(0x10), u32le(endSize),
    u32le(0), u32le(0xffffffff), u32le(0xffffffff), u32le(tagIdx),
  ]);
  return concat([start, end]);
}

function spliceInsert(buf, at, insert) {
  const out = new Uint8Array(buf.length + insert.length);
  out.set(buf.subarray(0, at), 0);
  out.set(insert, at);
  out.set(buf.subarray(at), at + insert.length);
  return out;
}

function parseStringPool(buf, chunkStart) {
  const off = chunkStart + 8;
  const stringCount = rd32(buf, off);
  const flags = rd32(buf, off + 8);
  const stringsStart = rd32(buf, off + 12);
  const isUtf8 = (flags & 0x100) !== 0;
  const offsetsBase = off + 20;
  const poolStart = chunkStart + stringsStart;
  const out = [];
  for (let i = 0; i < stringCount; i++) {
    const eo = rd32(buf, offsetsBase + i * 4);
    let p = poolStart + eo;
    if (isUtf8) {
      let cl = buf[p++]; if (cl & 0x80) cl = ((cl & 0x7f) << 8) | buf[p++];
      let bl = buf[p++]; if (bl & 0x80) bl = ((bl & 0x7f) << 8) | buf[p++];
      out.push(new TextDecoder('utf-8').decode(buf.subarray(p, p + bl)));
    } else {
      let cl = rd16(buf, p); p += 2;
      if (cl & 0x8000) { cl = ((cl & 0x7fff) << 16) | rd16(buf, p); p += 2; }
      out.push(utf16le(buf, p, cl));
    }
  }
  return out;
}

function utf16le(buf, p, chars) {
  let s = '';
  for (let i = 0; i < chars; i++) s += String.fromCharCode(rd16(buf, p + i * 2));
  return s;
}

function buildStringPool(strings) {
  const blobs = strings.map((s) => {
    const len = s.length;
    const head = len < 0x8000 ? u16le(len) : concat([u16le((len >>> 16) | 0x8000), u16le(len & 0xffff)]);
    const body = new Uint8Array(s.length * 2);
    for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); body[i * 2] = c & 0xff; body[i * 2 + 1] = (c >>> 8) & 0xff; }
    return concat([head, body, u16le(0)]);
  });
  const offsets = new Uint8Array(blobs.length * 4);
  let acc = 0;
  for (let i = 0; i < blobs.length; i++) { wr32(offsets, i * 4, acc); acc += blobs[i].length; }
  let blobSize = acc;
  const pad = (4 - (blobSize % 4)) % 4;
  blobSize += pad;
  const headerFields = 20;
  const stringsStart = 8 + headerFields + offsets.length;
  const total = stringsStart + blobSize;
  const header = concat([
    u16le(CHUNK_STRING_POOL), u16le(8 + headerFields), u32le(total),
    u32le(blobs.length), u32le(0), u32le(0), u32le(stringsStart), u32le(0),
  ]);
  return concat([header, offsets, ...blobs, new Uint8Array(pad)]);
}
