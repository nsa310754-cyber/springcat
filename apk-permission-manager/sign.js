/*
 * Client-side APK v1 (JAR) signing.
 *
 * Builds META-INF/MANIFEST.MF, META-INF/CERT.SF and META-INF/CERT.RSA the
 * same way `jarsigner` does (SHA-256 digests, detached PKCS#7 SignedData),
 * using a freshly generated, self-signed RSA certificate. Everything runs
 * locally - no key material or APK content ever leaves the browser.
 *
 * Depends on the global `forge` (node-forge), loaded separately.
 */
(function (global) {
  'use strict';

  var forge = global.forge || (typeof require === 'function' ? require('node-forge') : null);

  var CRLF = '\r\n';

  // Convert a (possibly large) Uint8Array to a binary string in chunks -
  // String.fromCharCode.apply(null, bytes) blows the call stack for
  // multi-megabyte inputs (classes.dex, resources.arsc, native libs...).
  function bytesToBinaryString(bytes) {
    var CHUNK = 0x8000;
    var parts = [];
    for (var i = 0; i < bytes.length; i += CHUNK) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK)));
    }
    return parts.join('');
  }

  function sha256Base64(bytes) {
    var md = forge.md.sha256.create();
    md.update(bytesToBinaryString(bytes));
    return forge.util.encode64(md.digest().getBytes());
  }

  // JAR manifest line folding: max 72 bytes/line (70 content bytes on the
  // first line, continuation lines start with one space + up to 69 bytes).
  function foldLine(line) {
    if (line.length <= 70) return line + CRLF;
    var out = line.slice(0, 70) + CRLF;
    var rest = line.slice(70);
    while (rest.length > 69) {
      out += ' ' + rest.slice(0, 69) + CRLF;
      rest = rest.slice(69);
    }
    out += ' ' + rest + CRLF;
    return out;
  }

  function attr(name, value) {
    return foldLine(name + ': ' + value);
  }

  // entries: array of { name, data: Uint8Array }, excluding any existing
  // META-INF signature files (those must already be stripped by the caller).
  function buildManifestAndSignature(entries) {
    var manifestMain = attr('Manifest-Version', '1.0') + CRLF;
    var perEntrySections = []; // { name, sectionText }

    entries.forEach(function (e) {
      var digest = sha256Base64(e.data);
      var section = attr('Name', e.name) + attr('SHA-256-Digest', digest) + CRLF;
      perEntrySections.push({ name: e.name, sectionText: section });
    });

    var manifestText = manifestMain + perEntrySections.map(function (s) { return s.sectionText; }).join('');
    var manifestBytes = asciiToBytes(manifestText);

    var manifestDigest = sha256Base64(manifestBytes);

    var sfMain = attr('Signature-Version', '1.0') + attr('SHA-256-Digest-Manifest', manifestDigest) + attr('Created-By', 'apk-permission-manager') + CRLF;
    var sfSections = perEntrySections.map(function (s) {
      var sectionBytes = asciiToBytes(s.sectionText);
      var sectionDigest = sha256Base64(sectionBytes);
      return attr('Name', s.name) + attr('SHA-256-Digest', sectionDigest) + CRLF;
    });
    var sfText = sfMain + sfSections.join('');
    var sfBytes = asciiToBytes(sfText);

    return { manifestBytes: manifestBytes, sfBytes: sfBytes };
  }

  function asciiToBytes(str) {
    var out = new Uint8Array(str.length);
    for (var i = 0; i < str.length; i++) out[i] = str.charCodeAt(i) & 0xFF;
    return out;
  }

  function generateSelfSignedIdentity(commonName) {
    var keys = forge.pki.rsa.generateKeyPair({ bits: 2048, e: 0x10001 });
    var cert = forge.pki.createCertificate();
    cert.publicKey = keys.publicKey;
    cert.serialNumber = '01' + forge.util.bytesToHex(forge.random.getBytesSync(8));
    cert.validity.notBefore = new Date(2000, 0, 1);
    cert.validity.notAfter = new Date(2137, 0, 1);
    var attrs = [{ name: 'commonName', value: commonName || 'apk-permission-manager' }];
    cert.setSubject(attrs);
    cert.setIssuer(attrs);
    cert.sign(keys.privateKey, forge.md.sha256.create());
    return { privateKey: keys.privateKey, certificate: cert };
  }

  // Returns DER (Uint8Array) of a detached PKCS#7 SignedData over sfBytes.
  function signDetachedPkcs7(sfBytes, identity) {
    var p7 = forge.pkcs7.createSignedData();
    p7.content = forge.util.createBuffer(bytesToBinaryString(sfBytes));
    p7.addCertificate(identity.certificate);
    p7.addSigner({
      key: identity.privateKey,
      certificate: identity.certificate,
      digestAlgorithm: forge.pki.oids.sha256,
      authenticatedAttributes: [
        { type: forge.pki.oids.contentType, value: forge.pki.oids.data },
        { type: forge.pki.oids.messageDigest }
      ]
    });
    p7.sign({ detached: true });
    var der = forge.asn1.toDer(p7.toAsn1()).getBytes();
    return asciiToBytes(der);
  }

  // Signs a set of zip entries (v1/JAR scheme). `entries` excludes any
  // pre-existing META-INF/MANIFEST.MF, *.SF, *.RSA/*.DSA/*.EC.
  // Returns the three new entries to add to the archive.
  function signEntries(entries, opts) {
    opts = opts || {};
    var identity = generateSelfSignedIdentity(opts.commonName);
    var built = buildManifestAndSignature(entries);
    var rsaDer = signDetachedPkcs7(built.sfBytes, identity);
    return {
      files: [
        { name: 'META-INF/MANIFEST.MF', data: built.manifestBytes },
        { name: 'META-INF/CERT.SF', data: built.sfBytes },
        { name: 'META-INF/CERT.RSA', data: rsaDer }
      ],
      certificatePem: forge.pki.certificateToPem(identity.certificate)
    };
  }

  var ApkSign = { signEntries: signEntries };

  if (typeof module !== 'undefined' && module.exports) module.exports = ApkSign;
  global.ApkSign = ApkSign;
})(typeof window !== 'undefined' ? window : global);
