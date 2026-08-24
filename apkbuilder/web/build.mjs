// Top-level: template APK + user inputs -> signed APK (browser).
import { zipRead, zipWrite, inflateRaw, deflateRaw, crc32 } from './engine.mjs';
import { Axml } from './axml.mjs';
import { generateKeystore, signV2 } from './sign.mjs';

const isSigFile = (n) => n.startsWith('META-INF/') && /\.(SF|RSA|MF|EC|DSA)$/i.test(n);

// config: {appName, packageId, versionName, versionCode, permissions:[...]}
// overrides: { "assets/game.html": Uint8Array, "res/mipmap-hdpi-v4/ic_launcher.png": Uint8Array, ... }
export async function buildApk(templateBytes, config, overrides) {
  const entries = zipRead(templateBytes);

  const manRec = entries.find((e) => e.name === 'AndroidManifest.xml');
  if (!manRec) throw new Error('template has no AndroidManifest.xml');
  const manBytes = manRec.method === 0 ? manRec.rawBytes() : await inflateRaw(manRec.rawBytes());

  const axml = Axml.parse(manBytes);
  axml.setPackage(config.packageId);
  axml.setVersionName(config.versionName);
  axml.setVersionCode(config.versionCode);
  axml.setApplicationLabel(config.appName);
  for (const p of (config.permissions || [])) axml.addUsesPermission(p);
  const newManifest = axml.toByteArray();

  const out = [];
  const used = new Set();
  for (const e of entries) {
    if (isSigFile(e.name)) continue;
    if (e.name === 'AndroidManifest.xml') {
      const data = await deflateRaw(newManifest);
      out.push({ name: e.name, method: 8, crc: crc32(newManifest), compSize: data.length, uncompSize: newManifest.length, data });
    } else if (overrides[e.name]) {
      used.add(e.name);
      out.push(await freshEntry(e.name, overrides[e.name]));
    } else {
      out.push({ name: e.name, method: e.method, crc: e.crc, compSize: e.compSize, uncompSize: e.uncompSize, data: e.rawBytes() });
    }
  }
  for (const name of Object.keys(overrides)) {
    if (!used.has(name)) out.push(await freshEntry(name, overrides[name]));
  }

  const unsigned = zipWrite(out);
  const keystore = await generateKeystore(config.appName || 'APK Builder');
  const signed = await signV2(unsigned, keystore);
  return { apk: signed, keystore };
}

async function freshEntry(name, content) {
  const data = await deflateRaw(content);
  return { name, method: 8, crc: crc32(content), compSize: data.length, uncompSize: content.length, data };
}

export { generateKeystore };
