/*
 * Ties together axml.js + manifest.js + sign.js to read an APK's
 * requested permissions and produce a re-signed APK with permissions
 * added/removed. Depends on the global `JSZip` being available (loaded
 * via <script> in the browser, or `require('jszip')` in Node tests).
 */
(function (global) {
  'use strict';

  var AXML = global.AXML || (typeof require === 'function' ? require('./axml.js') : null);
  var ManifestPerms = global.ManifestPerms || (typeof require === 'function' ? require('./manifest.js') : null);
  var ApkSign = global.ApkSign || (typeof require === 'function' ? require('./sign.js') : null);
  var JSZip = global.JSZip || (typeof require === 'function' ? require('jszip') : null);

  var SIG_FILE_RE = /^META-INF\/[^\/]+\.(SF|RSA|DSA|EC)$/i;
  var MANIFEST_MF_RE = /^META-INF\/MANIFEST\.MF$/i;

  function isSignatureFile(name) {
    return MANIFEST_MF_RE.test(name) || SIG_FILE_RE.test(name);
  }

  async function readManifestDoc(zip) {
    var entry = zip.file('AndroidManifest.xml');
    if (!entry) throw new Error('AndroidManifest.xml が見つかりません（APKとして無効です）。');
    var bytes = await entry.async('uint8array');
    return AXML.parse(bytes);
  }

  async function listPermissions(zip) {
    var doc = await readManifestDoc(zip);
    return ManifestPerms.listPermissions(doc);
  }

  function compressionMethodOf(zipObject) {
    try {
      var magic = zipObject._data && zipObject._data.compression && zipObject._data.compression.magic;
      if (magic === '\x00\x00') return 'STORE';
    } catch (e) { /* fall through to default */ }
    return 'DEFLATE';
  }

  // opts: { remove: string[], add: {name, tag}[] }
  // progress: optional function(stage, detail) called with human-readable status
  async function buildModifiedApk(zip, opts, progress) {
    var report = function (stage) { if (progress) progress(stage); };
    opts = opts || {};
    var remove = opts.remove || [];
    var add = opts.add || [];

    report('manifest を解析中…');
    var doc = await readManifestDoc(zip);

    remove.forEach(function (name) { ManifestPerms.removePermission(doc, name); });
    add.forEach(function (item) { ManifestPerms.addPermission(doc, item.name, item.tag); });

    var newManifestBytes = AXML.serialize(doc);

    report('APK の内容を読み込み中…');
    var passthroughEntries = []; // { name, data, compression }
    var names = Object.keys(zip.files);
    for (var i = 0; i < names.length; i++) {
      var name = names[i];
      var zobj = zip.files[name];
      if (zobj.dir) continue;
      if (isSignatureFile(name)) continue; // old v1 signature: dropped, we re-sign
      if (name === 'AndroidManifest.xml') continue; // replaced below
      var data = await zobj.async('uint8array');
      passthroughEntries.push({ name: name, data: data, compression: compressionMethodOf(zobj) });
    }
    passthroughEntries.unshift({ name: 'AndroidManifest.xml', data: newManifestBytes, compression: 'DEFLATE' });

    report('署名を作成中（この端末内だけで行われます）…');
    var signResult = ApkSign.signEntries(
      passthroughEntries.map(function (e) { return { name: e.name, data: e.data }; })
    );

    report('APK を再構築中…');
    var outZip = new JSZip();
    signResult.files.forEach(function (f) {
      outZip.file(f.name, f.data, { compression: 'DEFLATE' });
    });
    passthroughEntries.forEach(function (e) {
      outZip.file(e.name, e.data, { compression: e.compression });
    });

    var apkBytes = await outZip.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
    return { apkBytes: apkBytes, certificatePem: signResult.certificatePem };
  }

  var ApkCore = {
    listPermissions: listPermissions,
    buildModifiedApk: buildModifiedApk,
    isSignatureFile: isSignatureFile
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = ApkCore;
  global.ApkCore = ApkCore;
})(typeof window !== 'undefined' ? window : global);
