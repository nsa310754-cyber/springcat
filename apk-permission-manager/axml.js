/*
 * Minimal binary AndroidManifest.xml (AXML) reader/writer.
 * Implements just enough of the AOSP ResourceTypes.h chunk format to
 * round-trip a manifest: string pool, optional resource map, and the
 * flat namespace/element/cdata node stream. Runs in the browser and in
 * Node (no DOM / browser globals used), so the same file is used for
 * both the app and the test harness.
 */
(function (global) {
  'use strict';

  var CHUNK_STRING_POOL = 0x0001;
  var CHUNK_XML = 0x0003;
  var CHUNK_XML_START_NAMESPACE = 0x0100;
  var CHUNK_XML_END_NAMESPACE = 0x0101;
  var CHUNK_XML_START_ELEMENT = 0x0102;
  var CHUNK_XML_END_ELEMENT = 0x0103;
  var CHUNK_XML_CDATA = 0x0104;
  var CHUNK_XML_RESOURCE_MAP = 0x0180;

  var NO_INDEX = 0xFFFFFFFF;

  function decodeUtf16LEString(bytes, offset, charLen) {
    // Decode charLen UTF-16 code units starting at byte `offset`.
    var units = new Uint16Array(charLen);
    for (var i = 0; i < charLen; i++) {
      units[i] = bytes[offset + i * 2] | (bytes[offset + i * 2 + 1] << 8);
    }
    var s = '';
    var CHUNK = 8192;
    for (var i = 0; i < units.length; i += CHUNK) {
      s += String.fromCharCode.apply(null, units.subarray(i, i + CHUNK));
    }
    return s;
  }

  function readLen16(dv, pos) {
    var u0 = dv.getUint16(pos, true);
    if (u0 & 0x8000) {
      var u1 = dv.getUint16(pos + 2, true);
      return { value: ((u0 & 0x7FFF) << 16) | u1, size: 4 };
    }
    return { value: u0, size: 2 };
  }

  function readLen8(bytes, pos) {
    var b0 = bytes[pos];
    if (b0 & 0x80) {
      var b1 = bytes[pos + 1];
      return { value: ((b0 & 0x7F) << 8) | b1, size: 2 };
    }
    return { value: b0, size: 1 };
  }

  function parseStringPool(bytes, dv, base) {
    var chunkSize = dv.getUint32(base + 4, true);
    var stringCount = dv.getUint32(base + 8, true);
    var styleCount = dv.getUint32(base + 12, true);
    var flags = dv.getUint32(base + 16, true);
    var stringsStart = dv.getUint32(base + 20, true);
    var utf8 = (flags & 0x100) !== 0;

    var offsets = new Array(stringCount);
    for (var i = 0; i < stringCount; i++) {
      offsets[i] = dv.getUint32(base + 28 + i * 4, true);
    }

    var strings = new Array(stringCount);
    for (var i = 0; i < stringCount; i++) {
      var pos = base + stringsStart + offsets[i];
      if (utf8) {
        var l1 = readLen8(bytes, pos); // char length (unused)
        var l2 = readLen8(bytes, pos + l1.size); // byte length
        var dataPos = pos + l1.size + l2.size;
        var slice = bytes.subarray(dataPos, dataPos + l2.value);
        strings[i] = Buffer && Buffer.from ? Buffer.from(slice).toString('utf8') : utf8DecodeFallback(slice);
      } else {
        var lr = readLen16(dv, pos);
        strings[i] = decodeUtf16LEString(bytes, pos + lr.size, lr.value);
      }
    }

    return { chunkSize: chunkSize, strings: strings, styleCount: styleCount };
  }

  function utf8DecodeFallback(bytes) {
    if (typeof TextDecoder !== 'undefined') {
      return new TextDecoder('utf-8').decode(bytes);
    }
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return decodeURIComponent(escape(s));
  }

  function parse(buffer) {
    var bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
    var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    var rootType = dv.getUint16(0, true);
    if (rootType !== CHUNK_XML) {
      throw new Error('Not a binary XML document (unexpected root chunk type 0x' + rootType.toString(16) + ')');
    }
    var rootSize = dv.getUint32(4, true);

    var pos = 8; // past root chunk header
    if (dv.getUint16(pos, true) !== CHUNK_STRING_POOL) {
      throw new Error('Expected string pool chunk at offset ' + pos);
    }
    var pool = parseStringPool(bytes, dv, pos);
    var strings = pool.strings;
    pos += pool.chunkSize;

    var resourceMapIds = null;
    if (pos + 8 <= bytes.length && dv.getUint16(pos, true) === CHUNK_XML_RESOURCE_MAP) {
      var rmSize = dv.getUint32(pos + 4, true);
      var n = (rmSize - 8) / 4;
      resourceMapIds = new Array(n);
      for (var i = 0; i < n; i++) {
        resourceMapIds[i] = dv.getUint32(pos + 8 + i * 4, true);
      }
      pos += rmSize;
    }

    var nodes = [];
    var end = rootSize;
    while (pos < end) {
      var type = dv.getUint16(pos, true);
      var headerSize = dv.getUint16(pos + 2, true);
      var size = dv.getUint32(pos + 4, true);
      var lineNumber = dv.getUint32(pos + 8, true);
      var comment = dv.getUint32(pos + 12, true);

      if (type === CHUNK_XML_START_NAMESPACE || type === CHUNK_XML_END_NAMESPACE) {
        var prefix = dv.getUint32(pos + 16, true);
        var uri = dv.getUint32(pos + 20, true);
        nodes.push({
          type: type === CHUNK_XML_START_NAMESPACE ? 'nsStart' : 'nsEnd',
          lineNumber: lineNumber, comment: comment, prefix: prefix, uri: uri
        });
      } else if (type === CHUNK_XML_START_ELEMENT) {
        // attrExt fields sit right after the 16-byte node header, regardless
        // of the chunk's headerSize (which stays 16 for this chunk type too).
        var ns = dv.getUint32(pos + 16, true);
        var name = dv.getUint32(pos + 20, true);
        var attributeStart = dv.getUint16(pos + 24, true);
        var attributeSize = dv.getUint16(pos + 26, true);
        var attributeCount = dv.getUint16(pos + 28, true);
        var idIndex = dv.getUint16(pos + 30, true);
        var classIndex = dv.getUint16(pos + 32, true);
        var styleIndex = dv.getUint16(pos + 34, true);
        var attrArrayBase = pos + 16 + attributeStart;
        var attributes = [];
        for (var a = 0; a < attributeCount; a++) {
          var ab = attrArrayBase + a * attributeSize;
          attributes.push({
            ns: dv.getUint32(ab, true),
            name: dv.getUint32(ab + 4, true),
            rawValue: dv.getUint32(ab + 8, true),
            valueSize: dv.getUint16(ab + 12, true),
            valueRes0: dv.getUint8(ab + 14),
            valueType: dv.getUint8(ab + 15),
            valueData: dv.getUint32(ab + 16, true)
          });
        }
        nodes.push({
          type: 'elemStart', lineNumber: lineNumber, comment: comment,
          ns: ns, name: name, idIndex: idIndex, classIndex: classIndex, styleIndex: styleIndex,
          attributes: attributes
        });
      } else if (type === CHUNK_XML_END_ELEMENT) {
        var ns2 = dv.getUint32(pos + 16, true);
        var name2 = dv.getUint32(pos + 20, true);
        nodes.push({ type: 'elemEnd', lineNumber: lineNumber, comment: comment, ns: ns2, name: name2 });
      } else if (type === CHUNK_XML_CDATA) {
        var data = dv.getUint32(pos + 16, true);
        var vSize = dv.getUint16(pos + 20, true);
        var vRes0 = dv.getUint8(pos + 22);
        var vType = dv.getUint8(pos + 23);
        var vData = dv.getUint32(pos + 24, true);
        nodes.push({
          type: 'cdata', lineNumber: lineNumber, comment: comment, data: data,
          valueSize: vSize, valueRes0: vRes0, valueType: vType, valueData: vData
        });
      } else {
        // Unknown chunk type: keep raw bytes so we can round-trip untouched.
        nodes.push({ type: 'raw', bytes: bytes.slice(pos, pos + size) });
      }
      pos += size;
    }

    return { strings: strings, resourceMapIds: resourceMapIds, nodes: nodes };
  }

  // ---- serialization ----

  function utf8Encode(str) {
    if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(str);
    return new Uint8Array(Buffer.from(str, 'utf8'));
  }

  function buildStringPool(strings) {
    var stringCount = strings.length;
    var offsetsSize = stringCount * 4;
    var stringsStart = 28 + offsetsSize;

    var encoded = [];
    var dataLen = 0;
    for (var i = 0; i < stringCount; i++) {
      var s = strings[i];
      var len = s.length; // UTF-16 code unit count == JS string length
      var headBytes;
      if (len >= 0x8000) {
        headBytes = 4;
      } else {
        headBytes = 2;
      }
      var byteLen = headBytes + len * 2 + 2; // + null terminator
      encoded.push({ str: s, len: len, headBytes: headBytes, byteLen: byteLen });
      dataLen += byteLen;
    }
    var paddedDataLen = dataLen;
    while ((stringsStart + paddedDataLen) % 4 !== 0) paddedDataLen++;

    var chunkSize = stringsStart + paddedDataLen;
    var out = new Uint8Array(chunkSize);
    var dv = new DataView(out.buffer);

    dv.setUint16(0, CHUNK_STRING_POOL, true);
    dv.setUint16(2, 28, true);
    dv.setUint32(4, chunkSize, true);
    dv.setUint32(8, stringCount, true);
    dv.setUint32(12, 0, true); // styleCount
    dv.setUint32(16, 0, true); // flags: UTF-16, not sorted
    dv.setUint32(20, stringsStart, true);
    dv.setUint32(24, 0, true); // stylesStart

    var offset = 0;
    var dataPos = stringsStart;
    for (var i = 0; i < stringCount; i++) {
      dv.setUint32(28 + i * 4, offset, true);
      var e = encoded[i];
      var p = dataPos + offset;
      if (e.headBytes === 4) {
        dv.setUint16(p, 0x8000 | (e.len >>> 16), true);
        dv.setUint16(p + 2, e.len & 0xFFFF, true);
      } else {
        dv.setUint16(p, e.len, true);
      }
      var charPos = p + e.headBytes;
      for (var c = 0; c < e.len; c++) {
        dv.setUint16(charPos + c * 2, e.str.charCodeAt(c), true);
      }
      dv.setUint16(charPos + e.len * 2, 0, true); // terminator
      offset += e.byteLen;
    }

    return out;
  }

  function buildResourceMap(ids) {
    var chunkSize = 8 + ids.length * 4;
    var out = new Uint8Array(chunkSize);
    var dv = new DataView(out.buffer);
    dv.setUint16(0, CHUNK_XML_RESOURCE_MAP, true);
    dv.setUint16(2, 8, true);
    dv.setUint32(4, chunkSize, true);
    for (var i = 0; i < ids.length; i++) dv.setUint32(8 + i * 4, ids[i], true);
    return out;
  }

  function buildNode(node) {
    var out, dv;
    switch (node.type) {
      case 'nsStart':
      case 'nsEnd':
        out = new Uint8Array(24);
        dv = new DataView(out.buffer);
        dv.setUint16(0, node.type === 'nsStart' ? CHUNK_XML_START_NAMESPACE : CHUNK_XML_END_NAMESPACE, true);
        dv.setUint16(2, 16, true);
        dv.setUint32(4, 24, true);
        dv.setUint32(8, node.lineNumber >>> 0, true);
        dv.setUint32(12, node.comment >>> 0, true);
        dv.setUint32(16, node.prefix >>> 0, true);
        dv.setUint32(20, node.uri >>> 0, true);
        return out;

      case 'elemEnd':
        out = new Uint8Array(24);
        dv = new DataView(out.buffer);
        dv.setUint16(0, CHUNK_XML_END_ELEMENT, true);
        dv.setUint16(2, 16, true);
        dv.setUint32(4, 24, true);
        dv.setUint32(8, node.lineNumber >>> 0, true);
        dv.setUint32(12, node.comment >>> 0, true);
        dv.setUint32(16, node.ns >>> 0, true);
        dv.setUint32(20, node.name >>> 0, true);
        return out;

      case 'cdata':
        out = new Uint8Array(28);
        dv = new DataView(out.buffer);
        dv.setUint16(0, CHUNK_XML_CDATA, true);
        dv.setUint16(2, 16, true);
        dv.setUint32(4, 28, true);
        dv.setUint32(8, node.lineNumber >>> 0, true);
        dv.setUint32(12, node.comment >>> 0, true);
        dv.setUint32(16, node.data >>> 0, true);
        dv.setUint16(20, node.valueSize, true);
        dv.setUint8(22, node.valueRes0);
        dv.setUint8(23, node.valueType);
        dv.setUint32(24, node.valueData >>> 0, true);
        return out;

      case 'elemStart':
        var attrCount = node.attributes.length;
        var size = 36 + attrCount * 20;
        out = new Uint8Array(size);
        dv = new DataView(out.buffer);
        dv.setUint16(0, CHUNK_XML_START_ELEMENT, true);
        dv.setUint16(2, 16, true);
        dv.setUint32(4, size, true);
        dv.setUint32(8, node.lineNumber >>> 0, true);
        dv.setUint32(12, node.comment >>> 0, true);
        dv.setUint32(16, node.ns >>> 0, true);
        dv.setUint32(20, node.name >>> 0, true);
        dv.setUint16(24, 20, true); // attributeStart
        dv.setUint16(26, 20, true); // attributeSize
        dv.setUint16(28, attrCount, true);
        dv.setUint16(30, node.idIndex || 0, true);
        dv.setUint16(32, node.classIndex || 0, true);
        dv.setUint16(34, node.styleIndex || 0, true);
        for (var a = 0; a < attrCount; a++) {
          var at = node.attributes[a];
          var ab = 36 + a * 20;
          dv.setUint32(ab, at.ns >>> 0, true);
          dv.setUint32(ab + 4, at.name >>> 0, true);
          dv.setUint32(ab + 8, at.rawValue >>> 0, true);
          dv.setUint16(ab + 12, at.valueSize, true);
          dv.setUint8(ab + 14, at.valueRes0);
          dv.setUint8(ab + 15, at.valueType);
          dv.setUint32(ab + 16, at.valueData >>> 0, true);
        }
        return out;

      case 'raw':
        return node.bytes;

      default:
        throw new Error('Unknown node type: ' + node.type);
    }
  }

  function serialize(doc) {
    var poolBytes = buildStringPool(doc.strings);
    var rmBytes = doc.resourceMapIds ? buildResourceMap(doc.resourceMapIds) : new Uint8Array(0);
    var nodeBytesList = doc.nodes.map(buildNode);

    var totalSize = 8 + poolBytes.length + rmBytes.length;
    for (var i = 0; i < nodeBytesList.length; i++) totalSize += nodeBytesList[i].length;

    var out = new Uint8Array(totalSize);
    var dv = new DataView(out.buffer);
    dv.setUint16(0, CHUNK_XML, true);
    dv.setUint16(2, 8, true);
    dv.setUint32(4, totalSize, true);

    var pos = 8;
    out.set(poolBytes, pos); pos += poolBytes.length;
    out.set(rmBytes, pos); pos += rmBytes.length;
    for (var i = 0; i < nodeBytesList.length; i++) {
      out.set(nodeBytesList[i], pos);
      pos += nodeBytesList[i].length;
    }
    return out;
  }

  function findString(strings, value) {
    for (var i = 0; i < strings.length; i++) {
      if (strings[i] === value) return i;
    }
    return -1;
  }

  function internString(doc, value) {
    var idx = findString(doc.strings, value);
    if (idx !== -1) return idx;
    doc.strings.push(value);
    return doc.strings.length - 1;
  }

  var AXML = {
    parse: parse,
    serialize: serialize,
    findString: findString,
    internString: internString,
    NO_INDEX: NO_INDEX
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = AXML;
  }
  global.AXML = AXML;
})(typeof window !== 'undefined' ? window : global);
