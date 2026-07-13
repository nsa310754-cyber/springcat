/* ============================================================================
   LunaBASIC — the runtime that powers Lunax .exe programs
   A small, friendly BASIC-style interpreter. Async so INPUT / SLEEP work.
   ==========================================================================*/
(function (global) {
  "use strict";

  // ---- expression tokenizer -------------------------------------------------
  function tokenize(s) {
    const t = [];
    let i = 0;
    while (i < s.length) {
      const c = s[i];
      if (c === " " || c === "\t") { i++; continue; }
      if (c === '"') {
        let j = i + 1, str = "";
        while (j < s.length && s[j] !== '"') {
          if (s[j] === "\\" && j + 1 < s.length) { // simple escapes
            const n = s[j + 1];
            str += n === "n" ? "\n" : n === "t" ? "\t" : n;
            j += 2; continue;
          }
          str += s[j]; j++;
        }
        i = j + 1; t.push({ t: "str", v: str }); continue;
      }
      if (/[0-9]/.test(c) || (c === "." && /[0-9]/.test(s[i + 1] || ""))) {
        let j = i; while (j < s.length && /[0-9.]/.test(s[j])) j++;
        t.push({ t: "num", v: parseFloat(s.slice(i, j)) }); i = j; continue;
      }
      if (/[A-Za-z_]/.test(c)) {
        let j = i; while (j < s.length && /[A-Za-z0-9_]/.test(s[j])) j++;
        let name = s.slice(i, j);
        if (s[j] === "$") { name += "$"; j++; }
        i = j; t.push({ t: "id", v: name }); continue;
      }
      let two = s.substr(i, 2);
      if (two === "<=" || two === ">=" || two === "<>") { t.push({ t: "op", v: two }); i += 2; continue; }
      if ("=<>+-*/(),".includes(c)) { t.push({ t: "op", v: c }); i++; continue; }
      throw new Error("Syntax: unexpected '" + c + "'");
    }
    return t;
  }

  const KW_OPS = { AND: 1, OR: 1, NOT: 1, MOD: 1 };

  // ---- expression parser / evaluator ---------------------------------------
  class Expr {
    constructor(tokens, vm) { this.k = tokens; this.p = 0; this.vm = vm; }
    peek() { return this.k[this.p]; }
    next() { return this.k[this.p++]; }
    isKw(w) { const x = this.peek(); return x && x.t === "id" && x.v.toUpperCase() === w; }
    eatKw(w) { if (this.isKw(w)) { this.p++; return true; } return false; }

    parse() {
      const v = this.pOr();
      if (this.p < this.k.length) throw new Error("Syntax: trailing tokens");
      return v;
    }
    pOr() { let a = this.pAnd(); while (this.eatKw("OR")) { const b = this.pAnd(); a = (truthy(a) || truthy(b)) ? 1 : 0; } return a; }
    pAnd() { let a = this.pNot(); while (this.eatKw("AND")) { const b = this.pNot(); a = (truthy(a) && truthy(b)) ? 1 : 0; } return a; }
    pNot() { if (this.eatKw("NOT")) { return truthy(this.pNot()) ? 0 : 1; } return this.pCmp(); }
    pCmp() {
      let a = this.pAdd();
      const x = this.peek();
      if (x && x.t === "op" && ["=", "<>", "<", ">", "<=", ">="].includes(x.v)) {
        this.next(); const b = this.pAdd(); return compare(x.v, a, b);
      }
      return a;
    }
    pAdd() {
      let a = this.pMul();
      for (;;) {
        const x = this.peek();
        if (x && x.t === "op" && (x.v === "+" || x.v === "-")) {
          this.next(); const b = this.pMul();
          if (x.v === "+") a = (typeof a === "string" || typeof b === "string") ? toStr(a) + toStr(b) : a + b;
          else a = toNum(a) - toNum(b);
        } else break;
      }
      return a;
    }
    pMul() {
      let a = this.pUnary();
      for (;;) {
        const x = this.peek();
        if (x && x.t === "op" && (x.v === "*" || x.v === "/")) {
          this.next(); const b = this.pUnary();
          a = x.v === "*" ? toNum(a) * toNum(b) : toNum(a) / toNum(b);
        } else if (this.isKw("MOD")) {
          this.next(); const b = this.pUnary();
          a = toNum(a) % toNum(b);
        } else break;
      }
      return a;
    }
    pUnary() {
      const x = this.peek();
      if (x && x.t === "op" && x.v === "-") { this.next(); return -toNum(this.pUnary()); }
      if (x && x.t === "op" && x.v === "+") { this.next(); return this.pUnary(); }
      return this.pPrimary();
    }
    pPrimary() {
      const x = this.next();
      if (!x) throw new Error("Syntax: unexpected end of expression");
      if (x.t === "num") return x.v;
      if (x.t === "str") return x.v;
      if (x.t === "op" && x.v === "(") { const v = this.pOr(); const c = this.next(); if (!c || c.v !== ")") throw new Error("Syntax: missing )"); return v; }
      if (x.t === "id") {
        const nx = this.peek();
        if (nx && nx.t === "op" && nx.v === "(") { // function call
          this.next(); const args = [];
          if (!(this.peek() && this.peek().v === ")")) {
            args.push(this.pOr());
            while (this.peek() && this.peek().v === ",") { this.next(); args.push(this.pOr()); }
          }
          const c = this.next(); if (!c || c.v !== ")") throw new Error("Syntax: missing ) after " + x.v);
          return callFunc(x.v.toUpperCase(), args);
        }
        // variable
        return this.vm.getVar(x.v);
      }
      throw new Error("Syntax: unexpected token");
    }
  }

  function truthy(v) { return typeof v === "string" ? v.length > 0 : v !== 0; }
  function toNum(v) { return typeof v === "number" ? v : (parseFloat(v) || 0); }
  function toStr(v) {
    if (typeof v === "string") return v;
    if (Number.isInteger(v)) return String(v);
    return String(v);
  }
  function compare(op, a, b) {
    let r;
    if (typeof a === "string" || typeof b === "string") { a = toStr(a); b = toStr(b); r = a < b ? -1 : a > b ? 1 : 0; }
    else { a = toNum(a); b = toNum(b); r = a < b ? -1 : a > b ? 1 : 0; }
    switch (op) {
      case "=": return r === 0 ? 1 : 0;
      case "<>": return r !== 0 ? 1 : 0;
      case "<": return r < 0 ? 1 : 0;
      case ">": return r > 0 ? 1 : 0;
      case "<=": return r <= 0 ? 1 : 0;
      case ">=": return r >= 0 ? 1 : 0;
    }
  }

  function callFunc(name, a) {
    const n0 = a[0], n1 = a[1], n2 = a[2];
    switch (name) {
      case "RND": return a.length === 0 ? Math.random() : Math.floor(Math.random() * toNum(n0));
      case "INT": return Math.floor(toNum(n0));
      case "ABS": return Math.abs(toNum(n0));
      case "SQR": return Math.sqrt(toNum(n0));
      case "SGN": return Math.sign(toNum(n0));
      case "MOD": return toNum(n0) % toNum(n1);
      case "MIN": return Math.min.apply(null, a.map(toNum));
      case "MAX": return Math.max.apply(null, a.map(toNum));
      case "POW": return Math.pow(toNum(n0), toNum(n1));
      case "LEN": return toStr(n0).length;
      case "LEFT$": return toStr(n0).slice(0, toNum(n1));
      case "RIGHT$": return toStr(n0).slice(-toNum(n1) || toStr(n0).length);
      case "MID$": { const s = toStr(n0), i = toNum(n1) - 1; return a.length >= 3 ? s.substr(i, toNum(n2)) : s.substr(i); }
      case "UPPER$": return toStr(n0).toUpperCase();
      case "LOWER$": return toStr(n0).toLowerCase();
      case "TRIM$": return toStr(n0).trim();
      case "CHR$": return String.fromCharCode(toNum(n0));
      case "ASC": return toStr(n0).charCodeAt(0) || 0;
      case "STR$": return toStr(n0);
      case "VAL": return parseFloat(n0) || 0;
      case "TIME$": return new Date().toLocaleTimeString();
      case "DATE$": return new Date().toLocaleDateString();
      case "SPACE$": return " ".repeat(Math.max(0, toNum(n0)));
      default: throw new Error("Unknown function " + name + "()");
    }
  }

  // ---- top-level splitter (respects quotes & parens) -----------------------
  function splitTop(s, seps) {
    const out = []; let depth = 0, inStr = false, cur = "", sepUsed = "";
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (inStr) { cur += c; if (c === '"') inStr = false; continue; }
      if (c === '"') { inStr = true; cur += c; continue; }
      if (c === "(") depth++;
      if (c === ")") depth--;
      if (depth === 0 && seps.includes(c)) { out.push({ text: cur, sep: c }); cur = ""; sepUsed = c; continue; }
      cur += c;
    }
    out.push({ text: cur, sep: "" });
    return out;
  }

  // ---- the VM ---------------------------------------------------------------
  class LunaVM {
    constructor(io) {
      this.io = io; // { out(s), inputLine()->Promise<string>, cls(), color(c), sleep(ms), isHalted() }
      this.vars = {};
      this.halted = false;
      this.steps = 0;
      this.MAX_STEPS = 8_000_000;
    }

    getVar(name) {
      if (name in this.vars) return this.vars[name];
      return name.endsWith("$") ? "" : 0;
    }
    setVar(name, value) { this.vars[name] = value; }

    evalExpr(str) { return new Expr(tokenize(str), this).parse(); }

    // preprocess source into logical lines + label/loop maps
    prepare(src) {
      const raw = src.replace(/\r/g, "").split("\n");
      const lines = [];
      const labels = {};
      for (let ln of raw) {
        let text = ln.trim();
        if (text === "") continue;
        if (text === "'" || text.startsWith("'")) continue; // comment
        // strip leading line number
        const m = text.match(/^(\d+)\s+(.*)$/);
        if (m) { labels[m[1]] = lines.length; text = m[2]; }
        // label:  (identifier followed by colon and nothing meaningful)
        const lm = text.match(/^([A-Za-z_]\w*):\s*$/);
        if (lm) { labels[lm[1].toUpperCase()] = lines.length; continue; }
        lines.push(text);
      }
      // match FOR/NEXT and WHILE/WEND
      const nextOf = {}, forOf = {}, wendOf = {}, whileOf = {};
      const fstack = [], wstack = [];
      lines.forEach((t, i) => {
        const kw = t.split(/\s+/)[0].toUpperCase();
        if (kw === "FOR") fstack.push(i);
        else if (kw === "NEXT") { const f = fstack.pop(); if (f == null) throw new Error("NEXT without FOR (line " + (i + 1) + ")"); nextOf[f] = i; forOf[i] = f; }
        else if (kw === "WHILE") wstack.push(i);
        else if (kw === "WEND") { const w = wstack.pop(); if (w == null) throw new Error("WEND without WHILE (line " + (i + 1) + ")"); wendOf[w] = i; whileOf[i] = w; }
      });
      if (fstack.length) throw new Error("FOR without NEXT");
      if (wstack.length) throw new Error("WHILE without WEND");
      this.lines = lines; this.labels = labels;
      this.nextOf = nextOf; this.forOf = forOf; this.wendOf = wendOf; this.whileOf = whileOf;
    }

    async run(src) {
      this.prepare(src);
      const pc = { i: 0 };
      const forStack = [], gosubStack = [];
      const lines = this.lines;
      while (pc.i < lines.length) {
        if (this.halted || (this.io.isHalted && this.io.isHalted())) throw new Error("^C — program interrupted");
        if (++this.steps > this.MAX_STEPS) throw new Error("Program exceeded step limit (possible infinite loop)");
        if ((this.steps & 0x3fff) === 0) await new Promise((r) => setTimeout(r, 0)); // yield
        const line = lines[pc.i];
        const done = await this.exec(line, pc, forStack, gosubStack);
        if (done === "END") break;
        pc.i++;
      }
    }

    resolveTarget(t) {
      t = t.trim();
      if (t in this.labels) return this.labels[t];
      const up = t.toUpperCase();
      if (up in this.labels) return this.labels[up];
      throw new Error("Undefined label/line: " + t);
    }

    // execute a single logical line. returns "END" to stop, or void.
    async exec(text, pc, forStack, gosubStack) {
      const sp = text.indexOf(" ");
      const head = (sp === -1 ? text : text.slice(0, sp)).toUpperCase();
      const rest = sp === -1 ? "" : text.slice(sp + 1).trim();

      switch (head) {
        case "REM": return;
        case "END": case "STOP": return "END";
        case "CLS": this.io.cls(); return;
        case "BEEP": this.io.out(""); return;
        case "COLOR": this.io.color(rest.replace(/["']/g, "").trim()); return;
        case "SLEEP": await this.io.sleep(toNum(this.evalExpr(rest))); return;

        case "PRINT": case "?": return this.doPrint(rest);
        case "INPUT": return await this.doInput(rest);
        case "LET": return this.doAssign(rest);

        case "GOTO": pc.i = this.resolveTarget(rest) - 1; return;
        case "GOSUB": gosubStack.push(pc.i); pc.i = this.resolveTarget(rest) - 1; return;
        case "RETURN": { const r = gosubStack.pop(); if (r == null) throw new Error("RETURN without GOSUB"); pc.i = r; return; }

        case "IF": return await this.doIf(rest, pc, forStack, gosubStack);

        case "FOR": return this.doFor(text, pc, forStack);
        case "NEXT": return this.doNext(pc, forStack);
        case "WHILE": return this.doWhile(rest, pc);
        case "WEND": pc.i = this.whileOf[pc.i] - 1; return;

        default:
          // assignment?  var = expr   /  var$ = expr
          if (/^[A-Za-z_]\w*\$?\s*=/.test(text)) return this.doAssign(text);
          throw new Error("Unknown statement: " + head);
      }
    }

    doPrint(rest) {
      if (rest === "") { this.io.out("\n"); return; }
      let noNewline = false;
      let body = rest;
      if (body.endsWith(";")) { noNewline = true; body = body.slice(0, -1); }
      const parts = splitTop(body, ",;");
      let out = "";
      parts.forEach((seg, idx) => {
        const txt = seg.text.trim();
        if (txt !== "") out += toStr(this.evalExpr(txt));
        if (seg.sep === ",") out += " ";
      });
      this.io.out(out + (noNewline ? "" : "\n"));
    }

    async doInput(rest) {
      let prompt = "? ";
      let varName = rest;
      // INPUT "prompt"; var
      const sc = splitTop(rest, ";");
      if (sc.length >= 2 && /^\s*".*"\s*$/.test(sc[0].text)) {
        prompt = this.evalExpr(sc[0].text.trim());
        varName = sc.slice(1).map((x) => x.text).join(";").trim();
      }
      this.io.out(toStr(prompt));
      const val = await this.io.inputLine();
      if (varName.endsWith("$")) this.setVar(varName, val);
      else this.setVar(varName, parseFloat(val) || 0);
    }

    doAssign(text) {
      const eq = text.indexOf("=");
      let name = text.slice(0, eq).trim();
      if (/^LET\s+/i.test(name)) name = name.replace(/^LET\s+/i, "").trim();
      const val = this.evalExpr(text.slice(eq + 1).trim());
      if (name.endsWith("$")) this.setVar(name, toStr(val));
      else this.setVar(name, toNum(val));
    }

    async doIf(rest, pc, forStack, gosubStack) {
      // IF <cond> THEN <then> [ELSE <else>]
      const tIdx = findKeyword(rest, "THEN");
      if (tIdx === -1) throw new Error("IF without THEN");
      const cond = rest.slice(0, tIdx).trim();
      let after = rest.slice(tIdx + 4).trim();
      const eIdx = findKeyword(after, "ELSE");
      let thenPart = after, elsePart = "";
      if (eIdx !== -1) { thenPart = after.slice(0, eIdx).trim(); elsePart = after.slice(eIdx + 4).trim(); }
      const branch = truthy(this.evalExpr(cond)) ? thenPart : elsePart;
      if (branch === "") return;
      // bare number/label after THEN means GOTO
      if (/^\d+$/.test(branch) || (this.labels[branch.toUpperCase()] != null)) { pc.i = this.resolveTarget(branch) - 1; return; }
      return await this.exec(branch, pc, forStack, gosubStack);
    }

    doFor(text, pc, forStack) {
      // FOR v = a TO b [STEP s]
      const m = text.match(/^FOR\s+([A-Za-z_]\w*)\s*=\s*(.+)$/i);
      if (!m) throw new Error("Bad FOR syntax");
      const v = m[1]; let expr = m[2];
      const toIdx = findKeyword(expr, "TO");
      if (toIdx === -1) throw new Error("FOR without TO");
      const startE = expr.slice(0, toIdx).trim();
      let restE = expr.slice(toIdx + 2).trim();
      let step = 1;
      const stepIdx = findKeyword(restE, "STEP");
      let limitE = restE;
      if (stepIdx !== -1) { limitE = restE.slice(0, stepIdx).trim(); step = toNum(this.evalExpr(restE.slice(stepIdx + 4).trim())); }
      const start = toNum(this.evalExpr(startE));
      const limit = toNum(this.evalExpr(limitE));
      this.setVar(v, start);
      if ((step > 0 && start > limit) || (step < 0 && start < limit)) { pc.i = this.nextOf[pc.i]; return; } // skip body
      forStack.push({ v, limit, step, body: pc.i });
    }

    doNext(pc, forStack) {
      const f = forStack[forStack.length - 1];
      if (!f) throw new Error("NEXT without FOR");
      const nv = toNum(this.getVar(f.v)) + f.step;
      this.setVar(f.v, nv);
      if ((f.step > 0 && nv <= f.limit) || (f.step < 0 && nv >= f.limit)) { pc.i = f.body; }
      else { forStack.pop(); }
    }

    doWhile(rest, pc) {
      if (!truthy(this.evalExpr(rest))) pc.i = this.wendOf[pc.i]; // skip to after WEND
    }
  }

  // find a standalone keyword (word-boundary, not inside quotes) — returns index or -1
  function findKeyword(s, kw) {
    const re = new RegExp("\\b" + kw + "\\b", "i");
    let inStr = false;
    for (let i = 0; i < s.length; i++) {
      if (s[i] === '"') inStr = !inStr;
      if (!inStr) {
        const slice = s.slice(i);
        const m = slice.match(new RegExp("^" + kw + "\\b", "i"));
        if (m && (i === 0 || /[^A-Za-z0-9_$]/.test(s[i - 1]))) return i;
      }
    }
    return -1;
  }

  global.LunaVM = LunaVM;
})(window);
