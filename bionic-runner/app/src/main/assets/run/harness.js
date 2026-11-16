/*
 * Bionic Runner: HTML / JavaScript / TypeScript を動かすページに最初に読み込ませる補助スクリプト。
 *
 * アプリ側 (Java) が window.BR という窓口を用意する:
 *   BR.kind()   "html" / "js" / "ts"
 *   BR.mode()   "run" (実行モード: 出力を文字で返して終わる) / "game" (ゲームモード: 画面で動かす)
 *   BR.source() 実行するファイルの中身      BR.name() ファイル名
 *   BR.out(s) / BR.err(s) 出力を渡す        BR.exit(code) 終わったことを知らせる
 *
 * ここで行うこと:
 *   - console.log などの出力と、print(...) を BR へ渡す
 *   - エラーをつかまえる。実行モードでは文字で報告して終了、ゲームモードでは画面にエラーと
 *     コードを表示する
 *   - 実行モードでは、タイマーなどの「まだ終わっていない処理」が無くなったら exit 0 で終わる
 *   - ゲームモードでは、画面上のボタンからのキー入力をページへ届ける
 */
(function () {
  'use strict';
  var B = window.BR || {
    kind: function () { return 'html'; }, mode: function () { return 'game'; },
    source: function () { return ''; }, name: function () { return ''; },
    out: function () {}, err: function () {}, exit: function () {}
  };
  var kind = B.kind(), mode = B.mode();
  var exited = false;

  // ---------------------------------------------------------------- 出力

  function show(v, depth) {
    if (typeof v === 'string') return depth ? JSON.stringify(v) : v;
    if (v === undefined) return 'undefined';
    if (v === null) return 'null';
    if (typeof v === 'function') return '[Function' + (v.name ? ': ' + v.name : '') + ']';
    if (v instanceof Error) return v.stack || (v.name + ': ' + v.message);
    if (typeof v === 'object') {
      try {
        var seen = [];
        return JSON.stringify(v, function (k, x) {
          if (typeof x === 'object' && x !== null) {
            if (seen.indexOf(x) >= 0) return '[Circular]';
            seen.push(x);
          }
          if (typeof x === 'bigint') return String(x) + 'n';
          if (typeof x === 'function') return '[Function]';
          return x;
        });
      } catch (e) { return String(v); }
    }
    return String(v);
  }

  function join(args) {
    return Array.prototype.map.call(args, function (a) { return show(a, 0); }).join(' ');
  }

  ['log', 'info', 'debug', 'warn', 'error'].forEach(function (k) {
    var orig = console[k];
    console[k] = function () {
      if (!exited) (k === 'warn' || k === 'error' ? B.err : B.out).call(B, join(arguments) + '\n');
      if (orig) orig.apply(console, arguments);
    };
  });
  // print(a) で出力できるようにする (ブラウザ本来の print は「印刷」で、ここでは意味が無い)
  window.print = function () { console.log.apply(console, arguments); };
  if (mode === 'run') {
    window.alert = function (s) { console.log(s); };
    window.confirm = function () { return true; };
    window.prompt = function () { return ''; };
  }

  // -------------------------------------------------------------- エラー

  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** ゲームモード: 画面いっぱいにエラーとコードを表示する。line は 1 から数えた行 (分からなければ 0) */
  function showOverlay(title, line) {
    var old = document.getElementById('__br_error');
    if (old) old.remove();
    var box = document.createElement('div');
    box.id = '__br_error';
    box.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;'
      + 'background:#1a0b10;color:#e5e7eb;font:13px/1.45 monospace;overflow:auto;'
      + '-webkit-user-select:text;user-select:text;touch-action:auto';
    var html = '<div style="position:sticky;top:0;background:#b91c1c;color:#fff;padding:10px 12px;'
      + 'font-weight:bold;white-space:pre-wrap">⚠ ' + escapeHtml(title) + '</div>'
      + '<div style="padding:8px 0;white-space:pre">';
    var lines = String(B.source()).split('\n');
    for (var i = 0; i < lines.length; i++) {
      var hit = i + 1 === line;
      html += '<div' + (hit ? ' id="__br_error_line" style="background:#7f1d1d;color:#fff"' : '') + '>'
        + '<span style="display:inline-block;width:4em;text-align:right;color:'
        + (hit ? '#fecaca' : '#6b7280') + ';padding-right:1em">' + (i + 1) + '</span>'
        + escapeHtml(lines[i]) + ' </div>';
    }
    box.innerHTML = html + '</div>';
    (document.body || document.documentElement).appendChild(box);
    var at = document.getElementById('__br_error_line');
    if (at) at.scrollIntoView({ block: 'center' });
  }

  function fail(message, line) {
    var where = line > 0 ? ' (' + line + ' 行目)' : '';
    if (mode === 'game') {
      if (!exited) B.err(message + where + '\n');
      if (document.body) showOverlay(message + where, line);
      else document.addEventListener('DOMContentLoaded', function () { showOverlay(message + where, line); });
      return;
    }
    if (exited) return;
    B.err(message + where + '\n');
    exit(1);
  }

  window.addEventListener('error', function (e) {
    if (e.message === undefined) return; // 画像などの読み込み失敗。プログラムの誤りではない
    var m = e.error && e.error.name ? e.error.name + ': ' + e.error.message : e.message;
    // コードを差し込んで実行しているという、利用者には関係の無い前置きを取り除く
    m = String(m).replace(/Failed to execute 'appendChild' on 'Node': /, '').replace(/^Uncaught /, '');
    fail(m, userLine(e.lineno));
  });
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    fail(r instanceof Error ? r.name + ': ' + r.message : 'Unhandled rejection: ' + show(r, 0), 0);
  });

  /** ブラウザが報告した行を、利用者のファイルの行に直す (前置きを足して実行したぶんを引く) */
  var lineMap = null; // TypeScript: 変換後の行 → 元の行
  function userLine(n) {
    if (!n) return 0;
    if (lineMap) return lineMap[n] || 0;
    return n;
  }

  /**
   * TypeScript の変換器が出す対応表 (ソースマップ) から、「変換後の何行目が元の何行目か」を作る。
   * 変換器は文を 1 行ずつに並べ直すので、これが無いとエラーの行がずれる。
   */
  function buildLineMap(mapText) {
    var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var map = {}, srcLine = 0;
    var lines = JSON.parse(mapText).mappings.split(';');
    for (var i = 0; i < lines.length; i++) {
      var segs = lines[i].split(',');
      for (var j = 0; j < segs.length; j++) {
        var vals = [], v = 0, shift = 0;
        for (var k = 0; k < segs[j].length; k++) {
          var d = B64.indexOf(segs[j].charAt(k));
          v |= (d & 31) << shift;
          if (d & 32) { shift += 5; continue; }
          vals.push(v & 1 ? -(v >> 1) : v >> 1);
          v = 0;
          shift = 0;
        }
        if (vals.length >= 4) {
          srcLine += vals[2];
          if (!map[i + 1]) map[i + 1] = srcLine + 1; // その行で最初に出てくる対応を使う
        }
      }
    }
    return map;
  }

  // ------------------------------------------------- 実行モード: 終わりの判定

  var timers = {}, timerCount = 0, busy = 0, started = false;
  var realSetTimeout = window.setTimeout, realClearTimeout = window.clearTimeout;
  var realSetInterval = window.setInterval, realClearInterval = window.clearInterval;

  function exit(code) {
    if (exited) return;
    exited = true;
    B.exit(code);
  }

  function settle() {
    if (exited || !started || mode !== 'run') return;
    // タイマーも通信も残っていなければ終わり。Promise の続きが走れるよう、少し間を置いて確かめる
    realSetTimeout(function () {
      if (timerCount === 0 && busy === 0) realSetTimeout(function () {
        if (timerCount === 0 && busy === 0) exit(0);
      }, 20);
    }, 20);
  }

  if (mode === 'run') {
    window.setTimeout = function (fn, ms) {
      var args = Array.prototype.slice.call(arguments, 2);
      var id = realSetTimeout(function () {
        if (timers[id]) { delete timers[id]; timerCount--; }
        try { if (typeof fn === 'function') fn.apply(null, args); } finally { settle(); }
      }, ms);
      timers[id] = 1;
      timerCount++;
      return id;
    };
    window.clearTimeout = function (id) {
      if (timers[id]) { delete timers[id]; timerCount--; settle(); }
      realClearTimeout(id);
    };
    window.setInterval = function () {
      var id = realSetInterval.apply(window, arguments);
      timers[id] = 1;
      timerCount++;
      return id;
    };
    window.clearInterval = function (id) {
      if (timers[id]) { delete timers[id]; timerCount--; settle(); }
      realClearInterval(id);
    };
    if (window.fetch) {
      var realFetch = window.fetch;
      window.fetch = function () {
        busy++;
        var done = function () { busy--; settle(); };
        var p = realFetch.apply(window, arguments);
        p.then(done, done);
        return p;
      };
    }
    // process.exit(n) で終われるようにする (Node.js 向けに書かれたコードへの配慮)
    window.process = window.process || {
      argv: ['node', B.name()], env: {}, platform: 'android',
      exit: function (c) { exit(c === undefined ? 0 : c | 0); },
      stdout: { write: function (s) { if (!exited) B.out(String(s)); return true; } },
      stderr: { write: function (s) { if (!exited) B.err(String(s)); return true; } }
    };
  }

  // -------------------------------------------- ゲームモード: キー入力と画面合わせ

  /** 画面上のボタンから呼ばれる。type は "keydown" か "keyup" */
  window.__brKey = function (type, key, code, keyCode) {
    var e = new KeyboardEvent(type, { key: key, code: code, bubbles: true, cancelable: true });
    // 古い書き方 (e.keyCode / e.which) で調べるゲームのために、番号も持たせる
    try {
      Object.defineProperty(e, 'keyCode', { get: function () { return keyCode; } });
      Object.defineProperty(e, 'which', { get: function () { return keyCode; } });
    } catch (x) { /* 持たせられないブラウザでは key / code だけになる */ }
    (document.activeElement || document.body || document).dispatchEvent(e);
  };

  /** JS / TS だけのファイル用: キャンバスを画面いっぱいに (縦横比を保って) 拡大する */
  function fitCanvases() {
    var list = document.querySelectorAll('body > canvas');
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (!c.width || !c.height) continue;
      var k = Math.min(window.innerWidth / c.width, window.innerHeight / c.height);
      c.style.width = Math.floor(c.width * k) + 'px';
      c.style.height = Math.floor(c.height * k) + 'px';
    }
  }

  // ---------------------------------------------------- JS / TS のファイルを動かす

  function runScript(code) {
    var s = document.createElement('script');
    s.textContent = code;
    document.body.appendChild(s); // ここで実行される。文法の誤りは error イベントで届く
  }

  function loadScript(src, then) {
    var s = document.createElement('script');
    s.src = src;
    s.onload = then;
    s.onerror = function () { fail('TypeScript の変換器を読み込めませんでした', 0); };
    document.head.appendChild(s);
  }

  function transpile(source) {
    var ts = window.ts;
    var out = ts.transpileModule(source, {
      reportDiagnostics: true,
      fileName: B.name() || 'main.ts',
      compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS,
        sourceMap: true, alwaysStrict: false, removeComments: false,
        newLine: ts.NewLineKind.LineFeed }
    });
    var errors = (out.diagnostics || []).filter(function (d) { return d.category === 1; });
    if (errors.length) {
      var first = 0, text = errors.map(function (d) {
        var msg = ts.flattenDiagnosticMessageText(d.messageText, '\n');
        if (!d.file || d.start === undefined) return msg;
        var p = d.file.getLineAndCharacterOfPosition(d.start);
        if (!first) first = p.line + 1;
        return (p.line + 1) + ':' + (p.character + 1) + ': ' + msg;
      }).join('\n');
      return { error: '文法エラー\n' + text, line: first };
    }
    var map = null;
    try { map = buildLineMap(out.sourceMapText); } catch (e) { /* 行は分からないままにする */ }
    return { code: out.outputText.replace(/\n\/\/# sourceMappingURL=.*$/, ''), map: map };
  }

  var api = window.__br = {
    /** JS / TS 用の土台のページ (host.html) から呼ばれる */
    start: function () {
      var source = String(B.source());
      // require / module / exports が無くて止まらないようにする (中身は空)
      window.module = window.module || { exports: {} };
      window.exports = window.exports || window.module.exports;
      window.require = window.require || function (n) { throw new Error("require('" + n + "') は使えません"); };
      if (mode === 'game') {
        var canvas = document.getElementById('canvas');
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
        // コードが想定している id の要素が無ければ、その場で作る (HTML 無しでも動かすため)
        var realGet = document.getElementById.bind(document), first = true;
        document.getElementById = function (id) {
          var el = realGet(id);
          if (el || !id || String(id).indexOf('__br') === 0) return el;
          if (/canvas|game|board|screen|stage|tetris|field|^c$|^cv$|^cnv$/i.test(id)) {
            // 最初に求められたキャンバスには、用意してある 1 枚を当てる
            if (first && !canvas.dataset.used) { first = false; el = canvas; canvas.dataset.used = '1'; }
            else el = document.body.appendChild(document.createElement('canvas'));
          } else {
            el = document.body.appendChild(document.createElement('div'));
            el.style.cssText = 'position:fixed;left:8px;top:8px;color:#fff;font:14px sans-serif;'
              + 'pointer-events:none;text-shadow:0 0 3px #000';
          }
          el.id = id;
          return el;
        };
        realSetInterval(fitCanvases, 300);
        window.addEventListener('resize', fitCanvases);
      }
      var go = function (code) {
        started = true;
        runScript(code);
        if (mode === 'game') fitCanvases();
        settle();
      };
      if (kind !== 'ts') return go(source);
      loadScript('typescript.js', function () {
        var r;
        try { r = transpile(source); } catch (e) { r = { error: 'TypeScript の変換に失敗しました: ' + e.message, line: 0 }; }
        if (r.error) {
          started = true;
          fail(r.error, r.line);
          return;
        }
        lineMap = r.map;
        go(r.code);
      });
    }
  };

  // HTML のファイル: ページの読み込みが済んだら「始まった」とみなす
  if (kind === 'html') {
    window.addEventListener('load', function () { started = true; settle(); });
  }
})();
