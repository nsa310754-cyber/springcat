(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var state = {
    zip: null,
    fileName: '',
    fileSize: 0,
    packageName: '',
    original: [],      // [{name, tag}]
    removed: {},        // name -> true
    added: []           // [{name, tag}]
  };

  var dropzone = $('dropzone');
  var fileInput = $('fileInput');
  var appSection = $('appSection');
  var summary = $('summary');
  var permList = $('permList');
  var addedList = $('addedList');
  var addInput = $('addInput');
  var addBtn = $('addBtn');
  var addSuggestions = $('addSuggestions');
  var addMsg = $('addMsg');
  var applyBtn = $('applyBtn');
  var progressBox = $('progressBox');
  var progressText = $('progressText');
  var resultBox = $('resultBox');
  var errorBox = $('errorBox');
  var resetBtn = $('resetBtn');

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function showError(msg) {
    errorBox.textContent = msg;
    errorBox.classList.remove('hidden');
    errorBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
  function clearError() { errorBox.classList.add('hidden'); errorBox.textContent = ''; }

  function findPackageName(doc) {
    for (var i = 0; i < doc.nodes.length; i++) {
      var n = doc.nodes[i];
      if (n.type === 'elemStart' && doc.strings[n.name] === 'manifest') {
        for (var a = 0; a < n.attributes.length; a++) {
          var attr = n.attributes[a];
          if (doc.strings[attr.name] === 'package') {
            return doc.strings[attr.valueData] || doc.strings[attr.rawValue] || '';
          }
        }
      }
    }
    return '';
  }

  async function handleFile(file) {
    clearError();
    resultBox.classList.add('hidden');
    resultBox.innerHTML = '';
    if (!file) return;
    if (!/\.apk$/i.test(file.name)) {
      showError('.apk ファイルを選んでください。');
      return;
    }
    try {
      var buf = await file.arrayBuffer();
      var zip = await JSZip.loadAsync(buf);
      var manifestEntry = zip.file('AndroidManifest.xml');
      if (!manifestEntry) throw new Error('AndroidManifest.xml が見つかりません。APKファイルとして正しくないようです。');
      var manifestBytes = await manifestEntry.async('uint8array');
      var doc = AXML.parse(manifestBytes);

      state.zip = zip;
      state.fileName = file.name;
      state.fileSize = file.size;
      state.packageName = findPackageName(doc) || '(不明)';
      state.original = ManifestPerms.listPermissions(doc);
      state.removed = {};
      state.added = [];

      renderAll();
      appSection.classList.remove('hidden');
      appSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (e) {
      console.error(e);
      showError('APKの読み込みに失敗しました: ' + (e && e.message ? e.message : e));
    }
  }

  function permInfo(name) {
    var c = (window.PermissionsCatalog && window.PermissionsCatalog.CATALOG[name]) || null;
    return c;
  }

  function renderSummary() {
    var mb = (state.fileSize / (1024 * 1024)).toFixed(2);
    summary.innerHTML =
      '<div><span class="k">ファイル名</span><span class="v">' + esc(state.fileName) + '</span></div>' +
      '<div><span class="k">サイズ</span><span class="v">' + mb + ' MB</span></div>' +
      '<div><span class="k">パッケージ名</span><span class="v">' + esc(state.packageName) + '</span></div>' +
      '<div><span class="k">要求している権限</span><span class="v">' + state.original.length + ' 件</span></div>';
  }

  function renderPermList() {
    if (!state.original.length) {
      permList.innerHTML = '<p class="small">このAPKは uses-permission を要求していません。</p>';
      return;
    }
    permList.innerHTML = state.original.map(function (p, i) {
      var info = permInfo(p.name);
      var checked = !state.removed[p.name];
      var dangerBadge = info && info.danger ? '<span class="badge danger">危険な権限</span>' : '';
      var sdk23 = p.tag === 'uses-permission-sdk-23' ? '<span class="badge">SDK23+</span>' : '';
      return (
        '<label class="perm-row' + (checked ? '' : ' removed') + '">' +
          '<input type="checkbox" data-name="' + esc(p.name) + '" ' + (checked ? 'checked' : '') + '>' +
          '<span class="perm-main">' +
            '<span class="perm-name">' + esc(p.name) + '</span>' +
            (info ? '<span class="perm-label">' + esc(info.label) + '</span>' : '') +
          '</span>' +
          dangerBadge + sdk23 +
        '</label>'
      );
    }).join('');
  }

  function renderAddedList() {
    if (!state.added.length) {
      addedList.innerHTML = '<p class="small">追加予定の権限はありません。</p>';
      return;
    }
    addedList.innerHTML = state.added.map(function (p, i) {
      return (
        '<span class="chip">' + esc(p.name) +
          '<button type="button" class="chip-x" data-idx="' + i + '" aria-label="削除">&times;</button>' +
        '</span>'
      );
    }).join('');
  }

  function renderSuggestions() {
    var list = (window.PermissionsCatalog && window.PermissionsCatalog.COMMON_ADDABLE) || [];
    var have = {};
    state.original.forEach(function (p) { if (!state.removed[p.name]) have[p.name] = true; });
    state.added.forEach(function (p) { have[p.name] = true; });
    addSuggestions.innerHTML = list.filter(function (n) { return !have[n]; }).map(function (n) {
      var info = permInfo(n);
      var label = info ? info.label : '';
      return '<button type="button" class="suggest" data-name="' + esc(n) + '">' + esc(n.replace('android.permission.', '')) + (label ? '<small>' + esc(label) + '</small>' : '') + '</button>';
    }).join('');
  }

  function renderAll() {
    renderSummary();
    renderPermList();
    renderAddedList();
    renderSuggestions();
  }

  function tryAddPermission(rawName) {
    var name = (rawName || '').trim();
    addMsg.textContent = '';
    if (!name) return;
    if (!/^[A-Za-z0-9_.]+$/.test(name)) {
      addMsg.textContent = '権限名には英数字・ドット・アンダースコアのみ使用できます（例: android.permission.CAMERA）。';
      return;
    }
    var inOriginalActive = state.original.some(function (p) { return p.name === name && !state.removed[p.name]; });
    if (inOriginalActive) {
      addMsg.textContent = 'この権限は既に要求されています。';
      return;
    }
    var wasRemoved = state.original.some(function (p) { return p.name === name && state.removed[p.name]; });
    if (wasRemoved) {
      delete state.removed[name];
      renderAll();
      return;
    }
    if (state.added.some(function (p) { return p.name === name; })) {
      addMsg.textContent = '既に追加予定リストに入っています。';
      return;
    }
    state.added.push({ name: name });
    addInput.value = '';
    renderAll();
  }

  dropzone.addEventListener('dragover', function (e) { e.preventDefault(); dropzone.classList.add('drag'); });
  dropzone.addEventListener('dragleave', function () { dropzone.classList.remove('drag'); });
  dropzone.addEventListener('drop', function (e) {
    e.preventDefault();
    dropzone.classList.remove('drag');
    var f = e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) handleFile(f);
  });
  dropzone.addEventListener('click', function () { fileInput.click(); });
  fileInput.addEventListener('change', function () {
    if (fileInput.files[0]) handleFile(fileInput.files[0]);
  });

  permList.addEventListener('change', function (e) {
    var cb = e.target.closest('input[type="checkbox"]');
    if (!cb) return;
    var name = cb.getAttribute('data-name');
    if (cb.checked) delete state.removed[name];
    else state.removed[name] = true;
    renderPermList();
    renderSuggestions();
  });

  addedList.addEventListener('click', function (e) {
    var btn = e.target.closest('.chip-x');
    if (!btn) return;
    state.added.splice(Number(btn.getAttribute('data-idx')), 1);
    renderAll();
  });

  addBtn.addEventListener('click', function () { tryAddPermission(addInput.value); });
  addInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); tryAddPermission(addInput.value); } });
  addSuggestions.addEventListener('click', function (e) {
    var btn = e.target.closest('.suggest');
    if (!btn) return;
    tryAddPermission(btn.getAttribute('data-name'));
  });

  resetBtn.addEventListener('click', function () {
    state = { zip: null, fileName: '', fileSize: 0, packageName: '', original: [], removed: {}, added: [] };
    appSection.classList.add('hidden');
    resultBox.classList.add('hidden');
    resultBox.innerHTML = '';
    clearError();
    fileInput.value = '';
  });

  applyBtn.addEventListener('click', async function () {
    clearError();
    resultBox.classList.add('hidden');
    resultBox.innerHTML = '';
    var removeNames = Object.keys(state.removed);
    var addItems = state.added.slice();
    if (!removeNames.length && !addItems.length) {
      showError('変更がありません。権限のチェックを外すか、権限を追加してください。');
      return;
    }
    applyBtn.disabled = true;
    progressBox.classList.remove('hidden');
    try {
      var result = await ApkCore.buildModifiedApk(state.zip, { remove: removeNames, add: addItems }, function (stage) {
        progressText.textContent = stage;
      });
      var blob = new Blob([result.apkBytes], { type: 'application/vnd.android.package-archive' });
      var url = URL.createObjectURL(blob);
      var outName = state.fileName.replace(/\.apk$/i, '') + '-edited.apk';
      resultBox.innerHTML =
        '<p>✅ 完了しました。新しい署名で再パッケージ済みです。</p>' +
        '<a class="downloadBtn" href="' + url + '" download="' + esc(outName) + '">⬇ ' + esc(outName) + ' をダウンロード</a>' +
        '<p class="small">※ 元のアプリを端末にインストール済みの場合、署名が変わっているため<strong>上書き更新はできません</strong>。先に元のアプリをアンインストールしてから、この編集版をインストールしてください。</p>';
      resultBox.classList.remove('hidden');
    } catch (e) {
      console.error(e);
      showError('処理中にエラーが発生しました: ' + (e && e.message ? e.message : e));
    } finally {
      applyBtn.disabled = false;
      progressBox.classList.add('hidden');
    }
  });
})();
