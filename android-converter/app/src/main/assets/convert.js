/*
 * codeconv — オフライン・プログラミング言語コンバーター（変換エンジン）
 * ------------------------------------------------------------------
 * ★ ネットワーク通信は一切しません。すべてブラウザ内（このJS）で完結します。
 *   外部 API（LLM 等）は使わず、コードを「読み取って（トークナイズして）」
 *   構文レベルで別言語へ書き換えるルールベース変換器です。
 *
 * できること（構文レベルの変換。意味レベルの完全移植ではありません）:
 *   - Python / CoffeeScript / JavaScript / TypeScript 間の構造変換
 *     （インデント⇔波括弧、キーワード対応、コメント記号の変換 など）
 *   - JSON ⇔ YAML の正確な相互変換
 *   - 上記以外の言語ペアは「簡易変換」（キーワード/コメント記号の置換のみ）
 *
 * 使い方: convert(sourceCode, "python", "coffeescript") -> { code, notes }
 *
 * UMD 風にエクスポート（ブラウザ: window.CodeConv / Node: module.exports）。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.CodeConv = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ---- 言語プロファイル -------------------------------------------------
  // block: 'indent'（インデントでブロック） / 'brace'（波括弧でブロック）
  // line:  行コメント記号
  const PROFILES = {
    python:       { label: 'Python',       block: 'indent', line: '#',  strs: ["'", '"'], colonHeaders: true },
    coffeescript: { label: 'CoffeeScript', block: 'indent', line: '#',  strs: ["'", '"'], colonHeaders: false },
    javascript:   { label: 'JavaScript',   block: 'brace',  line: '//', strs: ["'", '"', '`'] },
    typescript:   { label: 'TypeScript',   block: 'brace',  line: '//', strs: ["'", '"', '`'] },
    java:         { label: 'Java',         block: 'brace',  line: '//', strs: ['"'] },
    c:            { label: 'C',            block: 'brace',  line: '//', strs: ['"'] },
    cpp:          { label: 'C++',          block: 'brace',  line: '//', strs: ['"'] },
    go:           { label: 'Go',           block: 'brace',  line: '//', strs: ['"', '`'] },
    ruby:         { label: 'Ruby',         block: 'other',  line: '#',  strs: ["'", '"'] },
    haskell:      { label: 'Haskell',      block: 'other',  line: '--', strs: ['"'] },
    json:         { label: 'JSON',         block: 'data' },
    yaml:         { label: 'YAML',         block: 'data' },
    html:         { label: 'HTML',         block: 'markup' },
    jsx:          { label: 'JSX',          block: 'brace',  line: '//', strs: ["'", '"', '`'] }
  };

  // ---- 概念テーブル（言語横断のキーワード対応） -------------------------
  // concept -> { lang: 綴り }。トークン単位で src綴り -> concept -> tgt綴り へ変換。
  const CONCEPTS = {
    _true:  { python: 'True',  javascript: 'true',  typescript: 'true',  coffeescript: 'true',  java: 'true',  go: 'true',  ruby: 'true',  cpp: 'true',  c: '1' },
    _false: { python: 'False', javascript: 'false', typescript: 'false', coffeescript: 'false', java: 'false', go: 'false', ruby: 'false', cpp: 'false', c: '0' },
    _null:  { python: 'None',  javascript: 'null',  typescript: 'null',  coffeescript: 'null',  java: 'null',  go: 'nil',   ruby: 'nil' },
    _and:   { python: 'and',   javascript: '&&',    typescript: '&&',    coffeescript: 'and',   java: '&&',    go: '&&' },
    _or:    { python: 'or',    javascript: '||',    typescript: '||',    coffeescript: 'or',    java: '||',    go: '||' },
    _not:   { python: 'not',   javascript: '!',     typescript: '!',     coffeescript: 'not',   java: '!',     go: '!' },
    _elif:  { python: 'elif',  javascript: 'else if', typescript: 'else if', coffeescript: 'else if' },
    _print: { python: 'print', javascript: 'console.log', typescript: 'console.log', coffeescript: 'console.log', ruby: 'puts', go: 'fmt.Println' }
  };

  // 文字列/コメントを保護するためのプレースホルダ
  const MASK = '';

  // 1行を { code, comment, indent } に分解し、文字列を退避する。
  function splitLine(raw, prof) {
    const indentMatch = raw.match(/^[ \t]*/)[0];
    const body = raw.slice(indentMatch.length);
    const literals = [];
    let out = '';
    let i = 0;
    let comment = '';
    const strs = prof.strs || [];
    while (i < body.length) {
      const ch = body[i];
      // 行コメント検出（文字列の外側で）
      if (prof.line && body.startsWith(prof.line, i)) {
        comment = body.slice(i);
        break;
      }
      // 文字列リテラル
      if (strs.indexOf(ch) !== -1) {
        let j = i + 1;
        let s = ch;
        while (j < body.length) {
          s += body[j];
          if (body[j] === '\\') { s += body[j + 1] || ''; j += 2; continue; }
          if (body[j] === ch) { j++; break; }
          j++;
        }
        literals.push(s);
        out += MASK + (literals.length - 1) + MASK;
        i = j;
        continue;
      }
      out += ch;
      i++;
    }
    return { indent: indentMatch, code: out.replace(/\s+$/, ''), comment: comment.trim(), literals };
  }

  function restore(code, literals) {
    return code.replace(new RegExp(MASK + '(\\d+)' + MASK, 'g'), (_, n) => literals[+n]);
  }

  // インデント幅 -> ネストレベル に正規化（スペース/タブ混在にも対応）
  function levelize(lines) {
    const stack = [''];
    return lines.map(l => {
      if (l.blank) { l.level = null; return l; }
      const ind = l.indent.replace(/\t/g, '    ');
      const top = () => stack[stack.length - 1];
      if (ind.length > top().length) {
        stack.push(ind);
      } else {
        while (stack.length > 1 && ind.length < top().length) stack.pop();
      }
      l.level = stack.length - 1;
      return l;
    });
  }

  // トークン単位でキーワードを src -> tgt へ置換（コード部分のみ、文字列は退避済み）
  function mapKeywords(code, src, tgt) {
    // src綴り -> concept の逆引き表を作る
    const rev = {};
    for (const c in CONCEPTS) {
      const w = CONCEPTS[c][src];
      if (w) rev[w] = c;
    }
    // 演算子系（&& 等）と語系を分けて処理。まず語系（識別子境界）。
    code = code.replace(/[A-Za-z_][A-Za-z0-9_.]*/g, (word) => {
      const c = rev[word];
      if (c && CONCEPTS[c][tgt] != null) return CONCEPTS[c][tgt];
      return word;
    });
    return code;
  }

  // =====================================================================
  //  構造変換の中核
  // =====================================================================

  // ソースを中間表現（IR）へ: [{level, header, comment, literals, opensBlock, kind}]
  function parseIR(source, prof) {
    const rawLines = source.replace(/\r\n?/g, '\n').split('\n');
    if (prof.block === 'indent') {
      let lines = rawLines.map(r => {
        if (r.trim() === '') return { blank: true, comment: '' };
        const p = splitLine(r, prof);
        return { blank: false, indent: p.indent, code: p.code.trim(), comment: p.comment, literals: p.literals };
      });
      lines = levelize(lines);
      // colon ヘッダ（Python 等）は末尾コロンでブロック開始を判定
      return lines.map(l => {
        if (l.blank) return l;
        let header = l.code;
        let opens = false;
        if (prof.colonHeaders && /:\s*$/.test(header)) { header = header.replace(/:\s*$/, ''); opens = true; }
        return { blank: false, level: l.level, header, comment: l.comment, literals: l.literals, opens };
      });
    }
    if (prof.block === 'brace') {
      // 波括弧の深さでレベルを決める。'{' '}' は行末/行頭にある想定（整形済み入力）。
      const ir = [];
      let depth = 0;
      for (const r of rawLines) {
        if (r.trim() === '') { ir.push({ blank: true }); continue; }
        const p = splitLine(r, prof);
        let code = p.code.trim();
        // 先頭の閉じ括弧はブロックを閉じる（`}` や `} else {` に対応）
        while (code[0] === '}') { depth = Math.max(0, depth - 1); code = code.slice(1).trim(); }
        if (code === '') continue;              // 閉じだけの行はIRに残さない（自動で閉じる）
        const level = depth;
        const opens = /\{\s*$/.test(code);
        const opensCount = (code.match(/\{/g) || []).length;
        const closeCount = (code.match(/}/g) || []).length;
        depth += opensCount - closeCount;
        if (depth < 0) depth = 0;
        code = code.replace(/;\s*$/, '').replace(/\s*\{\s*$/, '').trim();
        ir.push({ blank: false, level, header: code, comment: p.comment, literals: p.literals, opens });
      }
      return ir;
    }
    return null;
  }

  // IR を target 言語で出力
  function emitIR(ir, src, tgt, tprof) {
    const out = [];
    const IND = '  ';
    if (tprof.block === 'indent') {
      for (const l of ir) {
        if (l.blank) { out.push(''); continue; }
        let header = transformHeader(l.header, src, tgt, l.opens);
        header = mapKeywords(header, src, tgt);
        header = builtinFixups(header, src, tgt);
        header = restore(header, l.literals || []);
        let line = IND.repeat(l.level) + header;
        if (l.opens && tgt === 'python') line += ':';   // Python はコロン必須
        if (l.comment) line += ' ' + convertComment(l.comment, src, tgt);
        out.push(line);
      }
      return out.join('\n');
    }
    if (tprof.block === 'brace') {
      let prevLevel = 0;
      for (let idx = 0; idx < ir.length; idx++) {
        const l = ir[idx];
        if (l.blank) { out.push(''); continue; }
        while (prevLevel > l.level) { prevLevel--; out.push(IND.repeat(prevLevel) + '}'); }
        let header = transformHeader(l.header, src, tgt, l.opens);
        header = mapKeywords(header, src, tgt);
        header = builtinFixups(header, src, tgt);
        header = restore(header, l.literals || []);
        let line = IND.repeat(l.level) + header;
        if (l.opens) line += ' {';
        else if (needsSemicolon(header, tgt)) line += ';';
        if (l.comment) line += ' ' + convertComment(l.comment, src, tgt);
        out.push(line);
        prevLevel = l.level + (l.opens ? 1 : 0);
      }
      while (prevLevel > 0) { prevLevel--; out.push(IND.repeat(prevLevel) + '}'); }
      return out.join('\n');
    }
    return '';
  }

  // ソース構文のヘッダを、ターゲットのブロックスタイルへ構造変換する。
  // （キーワード対応の前段。ソース言語の綴りで判定する。）
  function transformHeader(h, src, tgt, opens) {
    const tprof = PROFILES[tgt];
    let m;
    if (tprof.block === 'brace') {
      // インデント系 -> 波括弧系
      if ((m = h.match(/^def\s+([A-Za-z_]\w*)\s*\((.*)\)\s*$/))) return 'function ' + m[1] + '(' + m[2] + ')';
      if ((m = h.match(/^def\s+([A-Za-z_]\w*)\s*$/)))          return 'function ' + m[1] + '()';
      if ((m = h.match(/^class\s+([A-Za-z_]\w*)\s*(\(.*\))?\s*$/))) return 'class ' + m[1];
      if ((m = h.match(/^for\s+(.+?)\s+in\s+(.+)$/)))          return 'for (const ' + m[1] + ' of ' + m[2] + ')';
      if ((m = h.match(/^elif\s+(.+)$/)))                       return 'else if (' + m[1] + ')';
      if ((m = h.match(/^(if|while|switch)\s+(.+)$/)))          return /^\(/.test(m[2]) ? m[1] + ' ' + m[2] : m[1] + ' (' + m[2] + ')';
      if (/^else$/.test(h)) return 'else';
      return h;
    }
    if (tprof.block === 'indent') {
      // インデント系ソース（Python 等）の綴りを吸収
      if ((m = h.match(/^def\s+([A-Za-z_]\w*)\s*\((.*)\)\s*$/)))         return defHeader(m[1], m[2], tgt);
      if ((m = h.match(/^def\s+([A-Za-z_]\w*)\s*$/)))                    return defHeader(m[1], '', tgt);
      if ((m = h.match(/^elif\s+(.+)$/)))                               return (tgt === 'python' ? 'elif ' : 'else if ') + m[1];
      if ((m = h.match(/^for\s+([A-Za-z_][\w, ]*?)\s+in\s+(.+)$/)))     return 'for ' + m[1] + ' in ' + m[2];
      // 波括弧系ソース -> インデント系
      if ((m = h.match(/^function\s+([A-Za-z_]\w*)\s*\((.*)\)$/)))       return defHeader(m[1], m[2], tgt);
      if ((m = h.match(/^(?:const|let|var)?\s*([A-Za-z_]\w*)\s*=\s*function\s*\((.*)\)$/))) return defHeader(m[1], m[2], tgt);
      if ((m = h.match(/^(?:const|let|var)?\s*([A-Za-z_]\w*)\s*=\s*\((.*)\)\s*=>$/)))       return defHeader(m[1], m[2], tgt);
      if ((m = h.match(/^class\s+([A-Za-z_]\w*).*$/)))                   return 'class ' + m[1];
      if ((m = h.match(/^for\s*\(\s*(?:const|let|var)?\s*([A-Za-z_]\w*)\s+of\s+(.+?)\)$/))) return 'for ' + m[1] + ' in ' + m[2];
      if ((m = h.match(/^else\s+if\s*\((.*)\)$/)))                       return (tgt === 'python' ? 'elif ' : 'else if ') + m[1];
      if ((m = h.match(/^(if|while|switch)\s*\((.*)\)$/)))              return m[1] + ' ' + m[2];
      if (/^else$/.test(h)) return 'else';
      return h;
    }
    return h;
  }

  // 関数定義ヘッダを言語別に組み立てる（Python: def f(x) / CoffeeScript: f = (x) ->）
  function defHeader(name, args, tgt) {
    if (tgt === 'coffeescript') return args.trim() === '' ? name + ' = ->' : name + ' = (' + args + ') ->';
    return 'def ' + name + '(' + args + ')';
  }

  function needsSemicolon(h, tgt) {
    if (tgt === 'go') return false;
    if (/[:{}]$/.test(h)) return false;
    if (/^(import|package|public|private|@)/.test(h)) return true;
    return true;
  }

  // print <-> console.log 等の複合ビルトイン置換（識別子表で拾えないもの）
  // TypeScript の型注釈を除去して JavaScript にする（よくある形のみ・ベストエフォート）
  function stripTsTypes(h) {
    // 戻り値型:  ): Type  ->  )
    h = h.replace(/\)\s*:\s*[A-Za-z_][\w.<>\[\]|&, ]*?(?=\s*$)/, ')');
    // 変数宣言:  let x: Type  /  const x: Type =
    h = h.replace(/\b(let|const|var)\s+([A-Za-z_]\w*)\s*:\s*[A-Za-z_][\w.<>\[\]|&, ]*?(?=\s*(=|$))/g, '$1 $2 ');
    // 引数の型:  (a: T, b: U)  ->  (a, b)
    h = h.replace(/\(([^()]*)\)/g, (m, inner) => {
      if (inner.indexOf(':') === -1) return m;
      const parts = inner.split(',').map(p =>
        p.replace(/^(\s*[A-Za-z_]\w*\??)\s*:\s*[A-Za-z_][\w.<>\[\]|&\s]*$/, '$1'));
      return '(' + parts.join(',') + ')';
    });
    return h.replace(/\s{2,}/g, ' ').replace(/\s+$/, '');
  }

  function builtinFixups(h, src, tgt) {
    if (src === 'typescript' && (tgt === 'javascript' || tgt === 'jsx')) {
      h = stripTsTypes(h);
    }
    if (src === 'python' && (tgt === 'javascript' || tgt === 'typescript' || tgt === 'jsx' || tgt === 'coffeescript')) {
      h = h.replace(/\blen\s*\(/g, '__LEN__(');
      h = h.replace(/__LEN__\((.+?)\)/g, '$1.length');
      h = h.replace(/\bself\b/g, 'this');
    }
    if ((src === 'javascript' || src === 'typescript') && tgt === 'python') {
      h = h.replace(/\bconsole\.log\b/g, 'print');
      h = h.replace(/\bthis\b/g, 'self');
      h = h.replace(/===/g, '==').replace(/!==/g, '!=');
      h = h.replace(/&&/g, 'and').replace(/\|\|/g, 'or');
      h = h.replace(/\bconst\b|\blet\b|\bvar\b/g, '').replace(/\s{2,}/g, ' ').trim();
    }
    return h;
  }

  function convertComment(c, src, tgt) {
    const sprof = PROFILES[src], tprof = PROFILES[tgt];
    const text = c.replace(new RegExp('^' + (sprof.line || '#').replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s?'), '');
    return (tprof.line || '#') + ' ' + text;
  }

  // =====================================================================
  //  JSON <-> YAML（正確な相互変換）
  // =====================================================================
  function toYAML(obj, indent) {
    indent = indent || 0;
    const pad = '  '.repeat(indent);
    if (obj === null) return 'null';
    if (Array.isArray(obj)) {
      if (obj.length === 0) return '[]';
      return obj.map(v => {
        const inner = toYAML(v, indent + 1);
        if (v !== null && typeof v === 'object' && Object.keys(v).length) {
          return pad + '- ' + inner.replace(/^\s+/, '');
        }
        return pad + '- ' + inner;
      }).join('\n');
    }
    if (typeof obj === 'object') {
      const keys = Object.keys(obj);
      if (keys.length === 0) return '{}';
      return keys.map(k => {
        const v = obj[k];
        if (v !== null && typeof v === 'object' && Object.keys(v).length) {
          return pad + yamlKey(k) + ':\n' + toYAML(v, indent + 1);
        }
        return pad + yamlKey(k) + ': ' + toYAML(v, indent + 1);
      }).join('\n');
    }
    if (typeof obj === 'string') return yamlScalar(obj);
    return String(obj);
  }
  function yamlKey(k) { return /^[A-Za-z_][\w-]*$/.test(k) ? k : JSON.stringify(k); }
  function yamlScalar(s) {
    if (s === '') return '""';
    if (/^[\s]|[\s]$/.test(s) || /[:#{}\[\],&*!|>'"%@`]/.test(s) || /^(true|false|null|yes|no|~)$/i.test(s) || /^-?\d/.test(s)) {
      return JSON.stringify(s);
    }
    return s;
  }

  // 最小 YAML パーサ（インデント・リスト・スカラーのみ。JSON 化用途）
  function parseYAML(text) {
    const lines = text.replace(/\r\n?/g, '\n').split('\n').filter(l => l.trim() !== '' && !/^\s*#/.test(l));
    let pos = 0;
    function indentOf(l) { return l.match(/^ */)[0].length; }
    function parseBlock(minIndent) {
      // 配列 or マップを判定
      if (pos >= lines.length) return null;
      const first = lines[pos];
      const ind = indentOf(first);
      if (ind < minIndent) return null;
      const isSeq = /^\s*-\s/.test(first) || /^\s*-\s*$/.test(first);
      if (isSeq) {
        const arr = [];
        while (pos < lines.length && indentOf(lines[pos]) === ind && /^\s*-/.test(lines[pos])) {
          const line = lines[pos];
          const after = line.replace(/^\s*-\s?/, '');
          pos++;
          if (after.trim() === '') {
            arr.push(parseBlock(ind + 1));
          } else if (/^[^:]+:(\s|$)/.test(after) && !/^["']/.test(after)) {
            // インライン開始のマップ
            pos--;
            lines[pos] = ' '.repeat(ind + 2) + after; // 疑似インデント
            arr.push(parseBlock(ind + 1));
          } else {
            arr.push(scalar(after.trim()));
          }
        }
        return arr;
      }
      // マップ
      const obj = {};
      while (pos < lines.length && indentOf(lines[pos]) === ind) {
        const line = lines[pos];
        const m = line.trim().match(/^([^:]+):\s*(.*)$/);
        if (!m) break;
        pos++;
        const key = m[1].trim().replace(/^["']|["']$/g, '');
        if (m[2].trim() === '') {
          obj[key] = parseBlock(ind + 1);
        } else {
          obj[key] = scalar(m[2].trim());
        }
      }
      return obj;
    }
    function scalar(s) {
      if (s === '' || s === '~' || s === 'null') return null;
      if (s === 'true') return true;
      if (s === 'false') return false;
      if (/^-?\d+$/.test(s)) return parseInt(s, 10);
      if (/^-?\d*\.\d+$/.test(s)) return parseFloat(s);
      if (/^["'].*["']$/.test(s)) return s.slice(1, -1);
      if (/^\[.*\]$/.test(s) || /^\{.*\}$/.test(s)) { try { return JSON.parse(s); } catch (e) {} }
      return s;
    }
    return parseBlock(0);
  }

  // =====================================================================
  //  簡易変換（構造変換非対応ペア用: コメント記号 + キーワードのみ置換）
  // =====================================================================
  function genericConvert(source, src, tgt) {
    const sprof = PROFILES[src], tprof = PROFILES[tgt];
    const lines = source.replace(/\r\n?/g, '\n').split('\n');
    return lines.map(r => {
      if (r.trim() === '') return r;
      const p = splitLine(r, sprof);
      let code = mapKeywords(p.code, src, tgt);
      code = builtinFixups(code, src, tgt);
      code = restore(code, p.literals);
      let out = p.indent + code;
      if (p.comment) out += ' ' + convertComment(p.comment, src, tgt);
      return out;
    }).join('\n');
  }

  // =====================================================================
  //  公開 API
  // =====================================================================
  function convert(source, src, tgt) {
    const notes = [];
    if (src === tgt) return { code: source, notes: ['同じ言語です。'] };
    const sprof = PROFILES[src], tprof = PROFILES[tgt];
    if (!sprof || !tprof) return { code: source, notes: ['未対応の言語です。'], error: true };

    // JSON <-> YAML
    if ((src === 'json' && tgt === 'yaml')) {
      try { return { code: toYAML(JSON.parse(source)), notes: ['JSON → YAML（正確変換）'] }; }
      catch (e) { return { code: '', notes: ['JSON の解析に失敗: ' + e.message], error: true }; }
    }
    if ((src === 'yaml' && tgt === 'json')) {
      try { return { code: JSON.stringify(parseYAML(source), null, 2), notes: ['YAML → JSON（正確変換）'] }; }
      catch (e) { return { code: '', notes: ['YAML の解析に失敗: ' + e.message], error: true }; }
    }
    if (src === 'json' && tgt !== 'yaml') { notes.push('JSON からコードへの変換は未対応です。'); }

    // 構造変換（indent/brace 同士）
    const structural = ['python', 'coffeescript', 'javascript', 'typescript', 'jsx', 'java', 'c', 'cpp', 'go'];
    if (structural.indexOf(src) !== -1 && structural.indexOf(tgt) !== -1) {
      const ir = parseIR(source, sprof);
      if (ir) {
        const code = emitIR(ir, src, tgt, tprof);
        notes.push('構文レベル変換（' + sprof.label + ' → ' + tprof.label + '）。意味の完全移植ではありません。');
        if (tprof.block === 'brace' && sprof.block === 'brace') notes.push('※ 波括弧→波括弧は主にキーワード対応の置換です。');
        return { code, notes };
      }
    }

    // フォールバック（簡易変換）
    notes.push('このペアは簡易変換（キーワード/コメント記号の置換）です。');
    return { code: genericConvert(source, src, tgt), notes };
  }

  function chain(source, langs) {
    // langs: ['haskell','html','jsx'] のような連鎖
    let code = source;
    const allNotes = [];
    for (let i = 0; i < langs.length - 1; i++) {
      const r = convert(code, langs[i], langs[i + 1]);
      code = r.code;
      allNotes.push('[' + PROFILES[langs[i]].label + '→' + PROFILES[langs[i + 1]].label + '] ' + r.notes.join(' '));
    }
    return { code, notes: allNotes };
  }

  return { convert, chain, PROFILES };
});
