import { readFileSync, writeFileSync } from 'fs';
function strip(src){
  return src.split('\n').filter(l=>{
    const t=l.trim();
    if(t.startsWith('import ')) return false;
    if(/^export\s*\{[^}]*\};?$/.test(t)) return false;
    return true;
  }).map(l=>l.replace(/^(\s*)export\s+/, '$1')).join('\n');
}
const engine = ['engine.mjs','axml.mjs','sign.mjs','build.mjs'].map(f=>strip(readFileSync(f,'utf8'))).join('\n\n');
const ui = readFileSync('ui.js','utf8');
// The lean WebView template shared with the Android app (single source of truth).
const b64 = readFileSync('../app/src/main/assets/template.apk').toString('base64');
const bundle = `const TEMPLATE_B64 = "${b64}";\n\n${engine}\n\n${ui}\n`;
const shell = readFileSync('shell.html','utf8');
const html = shell.replace('__BUNDLE__', () => bundle);
writeFileSync('index.html', html);
console.log('wrote index.html', (html.length/1024/1024).toFixed(2), 'MB');
