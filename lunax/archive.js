/* ============================================================================
   Lunax archive & app tools
   - gzip / gunzip via the browser's native (De)CompressionStream (no deps)
   - a small tar reader + writer
   - a PE (.exe) inspector so real PC apps can be "opened" and identified
   ==========================================================================*/
(function (global) {
  "use strict";

  const Arc = {};

  Arc.supported = typeof DecompressionStream !== "undefined" && typeof CompressionStream !== "undefined";

  // ---- bytes <-> base64 ----------------------------------------------------
  Arc.bytesToB64 = function (bytes) {
    let bin = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(bin);
  };
  Arc.b64ToBytes = function (b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  };

  // ---- gzip ----------------------------------------------------------------
  async function pipe(bytes, stream) {
    const s = new Blob([bytes]).stream().pipeThrough(stream);
    const ab = await new Response(s).arrayBuffer();
    return new Uint8Array(ab);
  }
  Arc.gunzip = (bytes) => pipe(bytes, new DecompressionStream("gzip"));
  Arc.gzip = (bytes) => pipe(bytes, new CompressionStream("gzip"));

  // ---- tar reader ----------------------------------------------------------
  function str(buf, o, len) {
    let s = "";
    for (let i = 0; i < len; i++) { const c = buf[o + i]; if (c === 0) break; s += String.fromCharCode(c); }
    return s;
  }
  Arc.untar = function (buf) {
    const files = [];
    let off = 0;
    let longName = null;
    while (off + 512 <= buf.length) {
      const name = str(buf, off, 100);
      if (name === "") break; // zero block = end of archive
      const sizeStr = str(buf, off + 124, 12).trim();
      const size = parseInt(sizeStr, 8) || 0;
      const typeflag = String.fromCharCode(buf[off + 156] || 0x30);
      const prefix = str(buf, off + 345, 155);
      const dataOff = off + 512;
      const dataEnd = dataOff + size;

      if (typeflag === "L") { // GNU long name — applies to next entry
        longName = str(buf, dataOff, size).replace(/\0+$/, "");
      } else if (typeflag === "x" || typeflag === "g") {
        // pax extended header — skip
      } else {
        let full = longName || (prefix ? prefix + "/" + name : name);
        longName = null;
        const isDir = typeflag === "5" || full.endsWith("/");
        if (isDir) files.push({ name: full.replace(/\/$/, ""), size: 0, isDir: true });
        else files.push({ name: full, size, isDir: false, data: buf.slice(dataOff, dataEnd) });
      }
      off = dataEnd + ((512 - (size % 512)) % 512);
    }
    return files;
  };

  // ---- tar writer ----------------------------------------------------------
  function octal(n, len) {
    let s = n.toString(8);
    while (s.length < len - 1) s = "0" + s;
    return s + "\0";
  }
  function tarHeader(name, size) {
    const h = new Uint8Array(512);
    const put = (s, o, len) => { for (let i = 0; i < s.length && i < len; i++) h[o + i] = s.charCodeAt(i); };
    put(name.slice(0, 100), 0, 100);
    put("0000644", 100, 8);           // mode
    put("0000000", 108, 8);           // uid
    put("0000000", 116, 8);           // gid
    put(octal(size, 12), 124, 12);    // size
    put(octal(Math.floor(Date.now() / 1000), 12), 136, 12); // mtime
    put("        ", 148, 8);          // checksum placeholder (spaces)
    h[156] = 0x30;                    // typeflag '0' regular
    put("ustar\0", 257, 6);
    put("00", 263, 2);
    let sum = 0;
    for (let i = 0; i < 512; i++) sum += h[i];
    put(octal(sum, 7).slice(0, 6) + "\0 ", 148, 8);
    return h;
  }
  Arc.tar = function (files) {
    const parts = [];
    for (const f of files) {
      parts.push(tarHeader(f.name, f.data.length));
      parts.push(f.data);
      const pad = (512 - (f.data.length % 512)) % 512;
      if (pad) parts.push(new Uint8Array(pad));
    }
    parts.push(new Uint8Array(1024)); // two zero blocks
    let total = 0; parts.forEach((p) => (total += p.length));
    const out = new Uint8Array(total);
    let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  };

  // ---- PE (.exe / .dll) inspector -----------------------------------------
  const u16 = (b, o) => b[o] | (b[o + 1] << 8);
  const u32 = (b, o) => (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
  Arc.inspectPE = function (bytes) {
    if (bytes.length < 64 || bytes[0] !== 0x4d || bytes[1] !== 0x5a) return { isPE: false };
    const peOff = u32(bytes, 0x3c);
    if (peOff + 24 > bytes.length || bytes[peOff] !== 0x50 || bytes[peOff + 1] !== 0x45) {
      return { isPE: false, dos: true }; // MZ but no PE (old DOS exe)
    }
    const machine = u16(bytes, peOff + 4);
    const sections = u16(bytes, peOff + 6);
    const chars = u16(bytes, peOff + 22);
    const optOff = peOff + 24;
    const magic = u16(bytes, optOff);
    const bits = magic === 0x20b ? 64 : 32;
    const subsystem = u16(bytes, optOff + (bits === 64 ? 68 : 68));
    const machines = { 0x14c: "Intel x86 (32-bit)", 0x8664: "AMD/Intel x64 (64-bit)", 0x1c0: "ARM", 0xaa64: "ARM64", 0x1c4: "ARM Thumb-2", 0x200: "Itanium" };
    const subs = { 1: "Native / driver", 2: "Windows GUI app", 3: "Windows console app", 9: "Windows CE GUI" };
    const isDll = (chars & 0x2000) !== 0;
    return {
      isPE: true, bits,
      machine: machines[machine] || ("machine 0x" + machine.toString(16)),
      subsystem: subs[subsystem] || ("subsystem #" + subsystem),
      sections, isDll,
    };
  };

  // classify by name/bytes
  Arc.isArchiveName = (n) => /\.(tar\.gz|tgz|tar|gz)$/i.test(n);
  Arc.archiveKind = (n) => {
    n = n.toLowerCase();
    if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return "tar.gz";
    if (n.endsWith(".tar")) return "tar";
    if (n.endsWith(".gz")) return "gz";
    return null;
  };

  // decompress+list an archive stored as raw bytes. returns [{name,size,isDir,data?}]
  Arc.listArchive = async function (name, bytes) {
    const k = Arc.archiveKind(name);
    if (k === "tar") return Arc.untar(bytes);
    if (k === "gz") { // single gzipped file
      const raw = await Arc.gunzip(bytes);
      const inner = name.replace(/\.gz$/i, "");
      return [{ name: inner, size: raw.length, isDir: false, data: raw }];
    }
    // tar.gz / tgz
    const tarBytes = await Arc.gunzip(bytes);
    return Arc.untar(tarBytes);
  };

  global.Arc = Arc;
})(window);
