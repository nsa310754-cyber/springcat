/* ---- UI logic (engine functions are in scope; TEMPLATE_B64 injected) ---- */
const $ = (id) => document.getElementById(id);

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const TEMPLATE = b64ToBytes(TEMPLATE_B64);

const STARTER = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>My Game</title>
<style>
  html,body{margin:0;height:100%;background:#111;color:#eee;
    display:flex;align-items:center;justify-content:center;font-family:sans-serif}
  #t{font-size:8vw}
</style>
</head>
<body>
  <div id="t">タップ: <span id="n">0</span></div>
  <script>
    var n=0;
    document.body.addEventListener('click',function(){
      n++; document.getElementById('n').textContent=n;
    });
  <\/script>
</body>
</html>
`;
$('html').value = STARTER;

const PERMISSIONS = [
  ['android.permission.INTERNET', 'インターネット通信', true],
  ['android.permission.VIBRATE', 'バイブレーション', false],
  ['android.permission.CAMERA', 'カメラ', false],
  ['android.permission.RECORD_AUDIO', 'マイク', false],
  ['android.permission.POST_NOTIFICATIONS', '通知', false],
  ['android.permission.ACCESS_FINE_LOCATION', '位置情報', false],
  ['android.permission.READ_EXTERNAL_STORAGE', '写真/動画の読み取り', false],
];
$('perms').innerHTML = PERMISSIONS.map(([p, label, on]) =>
  `<label class="perm"><input type="checkbox" value="${p}" ${on ? 'checked' : ''}>${label}</label>`).join('');

// auto package id from app name
$('appName').addEventListener('input', () => {
  const slug = $('appName').value.toLowerCase().replace(/[^a-z0-9]+/g, '') || 'app';
  $('pkg').value = 'com.example.' + (/^[0-9]/.test(slug) ? 'a' + slug : slug);
});

// tabs
let mode = 'code';
document.querySelectorAll('.tab').forEach((t) => {
  t.addEventListener('click', () => {
    mode = t.dataset.mode;
    document.querySelectorAll('.tab').forEach((x) => x.setAttribute('aria-selected', x === t));
    $('pane-code').classList.toggle('hide', mode !== 'code');
    $('pane-file').classList.toggle('hide', mode !== 'file');
    $('pane-files').classList.toggle('hide', mode !== 'files');
  });
});

// icon picker
let iconBytes = null;
$('iconBtn').onclick = () => $('iconFile').click();
$('iconFile').onchange = async (e) => {
  const f = e.target.files[0]; if (!f) return;
  iconBytes = new Uint8Array(await f.arrayBuffer());
  const url = URL.createObjectURL(f);
  $('iconPrev').src = url; $('iconPrev').style.display = 'block';
};

// single html file
let singleFile = null;
$('fileBtn').onclick = () => $('fileInput').click();
$('fileInput').onchange = (e) => { singleFile = e.target.files[0] || null; $('fileNote').textContent = singleFile ? '選択中: ' + singleFile.name : '未選択'; };

// multiple files
let multiFiles = [];
$('filesBtn').onclick = () => $('filesInput').click();
$('filesInput').onchange = (e) => {
  multiFiles = Array.from(e.target.files || []);
  $('filesNote').textContent = multiFiles.length ? multiFiles.length + '個選択（' + multiFiles.map((f) => f.name).slice(0, 4).join(', ') + '…）' : '未選択';
};

// resize icon into all densities via <canvas> (iOS-friendly)
async function buildIconOverrides(bytes) {
  const bmp = await createImageBitmap(new Blob([bytes]));
  const dens = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  const out = {};
  for (const [k, s] of Object.entries(dens)) {
    const c = document.createElement('canvas'); c.width = s; c.height = s;
    c.getContext('2d').drawImage(bmp, 0, 0, s, s);
    const blob = await new Promise((res) => c.toBlob(res, 'image/png'));
    const png = new Uint8Array(await blob.arrayBuffer());
    out[`res/mipmap-${k}-v4/ic_launcher.png`] = png;
    out[`res/mipmap-${k}-v4/ic_launcher_round.png`] = png;
  }
  return out;
}

async function collectGameOverrides() {
  const ov = {};
  if (mode === 'code') {
    ov['assets/game.html'] = new TextEncoder().encode($('html').value);
  } else if (mode === 'file') {
    if (!singleFile) throw new Error('HTMLファイルを選んでください');
    ov['assets/game.html'] = new Uint8Array(await singleFile.arrayBuffer());
  } else {
    if (!multiFiles.length) throw new Error('ファイルを選んでください');
    let entry = null;
    for (const f of multiFiles) {
      const rel = (f.webkitRelativePath || f.name).replace(/^[^/]+\//, '');
      ov['assets/' + rel] = new Uint8Array(await f.arrayBuffer());
      const base = rel.toLowerCase();
      if (!entry && (base === 'game.html' || base === 'index.html' || base.endsWith('/game.html') || base.endsWith('/index.html'))) entry = 'assets/' + rel;
    }
    if (!entry) throw new Error('index.html か game.html を含めてください');
    if (entry !== 'assets/game.html') { ov['assets/game.html'] = ov[entry]; delete ov[entry]; }
  }
  return ov;
}

function setStatus(msg, err) { const s = $('status'); s.textContent = msg; s.classList.toggle('err', !!err); }

let dlUrls = [];
function download(name, bytes, mime, secondary) {
  const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
  dlUrls.push(url);
  const a = document.createElement('a');
  a.href = url; a.download = name; a.textContent = name;
  if (secondary) a.className = 'sec';
  $('downloads').appendChild(a);
}

$('go').onclick = async () => {
  const btn = $('go');
  btn.disabled = true;
  $('result').classList.remove('show');
  $('downloads').innerHTML = '';
  dlUrls.forEach(URL.revokeObjectURL); dlUrls = [];
  setStatus('');
  btn.innerHTML = '<span class="spin"></span> 生成中…';
  try {
    const pkg = $('pkg').value.trim();
    if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(pkg)) throw new Error('パッケージIDの形式が正しくありません (例: com.example.mygame)');
    const overrides = await collectGameOverrides();
    if (iconBytes) Object.assign(overrides, await buildIconOverrides(iconBytes));
    const perms = Array.from(document.querySelectorAll('#perms input:checked')).map((i) => i.value);
    const appName = $('appName').value || 'App';
    const cfg = { appName, packageId: pkg, versionName: $('vname').value || '1.0', versionCode: parseInt($('vcode').value || '1', 10) || 1, permissions: perms };

    const { apk, keystore } = await buildApk(TEMPLATE, cfg, overrides);

    const safe = appName.replace(/[^\w.-]+/g, '_') || 'app';
    download(safe + '.apk', apk, 'application/vnd.android.package-archive');
    const info = `alias=key0\nsha256=${await fingerprint(keystore.certDer)}\n\n※ この鍵は毎回新しく生成されます。更新版を作るときは同じ鍵が必要（フル機能はAndroidアプリ版）。`;
    download('keystore-info.txt', new TextEncoder().encode(info), 'text/plain', true);

    $('resultInfo').textContent = `${safe}.apk（${(apk.length / 1048576).toFixed(1)} MB）· APK署名 v2 · package ${pkg}`;
    $('result').classList.add('show');
    setStatus('✓ 署名付きAPKを作成しました。下のボタンから保存してください。');
  } catch (e) {
    setStatus('エラー: ' + (e && e.message ? e.message : e), true);
  } finally {
    btn.disabled = false;
    btn.textContent = 'APKを生成';
  }
};

async function fingerprint(certDer) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', certDer));
  return Array.from(d).map((b) => b.toString(16).padStart(2, '0').toUpperCase()).join(':');
}

// ready
$('go').disabled = false;
$('go').textContent = 'APKを生成';
