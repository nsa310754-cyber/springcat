/* ============================================================================
   LunaxOS — a virtual environment for phones that can't run .exe files.
   Handles the filesystem, terminal shell, file manager, editor and windows.
   ==========================================================================*/
(function () {
  "use strict";

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const now = () => Date.now();

  /* ---------------------------------------------------------------- sample apps */
  const SAMPLES = {
    "hello.exe": `REM ---- hello.exe : your first Lunax program ----
COLOR cyan
PRINT "==============================="
PRINT "   Welcome to LunaxOS"
PRINT "==============================="
COLOR white
INPUT "What is your name? "; name$
COLOR green
PRINT "Hello, " + name$ + "! Nice to meet you."
PRINT "You are running a real .exe on your phone."
`,
    "guess.exe": `REM ---- guess.exe : number guessing game ----
COLOR magenta
PRINT "*** GUESS THE NUMBER ***"
COLOR white
secret = RND(100) + 1
tries = 0
PRINT "I'm thinking of a number from 1 to 100."
loop:
INPUT "Your guess> "; g
tries = tries + 1
IF g < secret THEN PRINT "Too low!  (up)"
IF g > secret THEN PRINT "Too high! (down)"
IF g = secret THEN GOTO win
GOTO loop
win:
COLOR green
PRINT "Correct! The number was " + STR$(secret)
PRINT "It took you " + STR$(tries) + " tries."
`,
    "fib.exe": `REM ---- fib.exe : fibonacci sequence ----
COLOR yellow
PRINT "Fibonacci numbers"
COLOR white
INPUT "How many? "; n
a = 0
b = 1
FOR i = 1 TO n
  PRINT STR$(i) + ": " + STR$(a)
  t = a + b
  a = b
  b = t
NEXT i
COLOR green
PRINT "done."
`,
    "clock.exe": `REM ---- clock.exe : a tiny live clock (5 ticks) ----
COLOR cyan
FOR i = 1 TO 5
  CLS
  PRINT "  LUNAX CLOCK"
  PRINT "  ----------"
  PRINT "  " + TIME$()
  PRINT "  " + DATE$()
  SLEEP 1000
NEXT i
PRINT "tick tock... bye!"
`,
    "matrix.exe": `REM ---- matrix.exe : digital rain-ish demo ----
COLOR green
FOR row = 1 TO 12
  line$ = ""
  FOR col = 1 TO 24
    line$ = line$ + STR$(RND(2))
  NEXT col
  PRINT line$
  SLEEP 90
NEXT row
PRINT "wake up..."
`,
  };

  const README = `Welcome to LunaxOS.

This is a virtual environment. You cannot run native Windows .exe files
on a phone, so Lunax gives you its OWN kind of .exe — small programs
written in LunaBASIC that really execute, right here in your browser.

Try it:
  * Open the Terminal, type:   run hello.exe
  * Or open Files and tap the green RUN button on any .exe
  * Write your own in the Editor and save it as   myapp.exe

Type 'help' in the terminal to see every command.
`;

  /* ---------------------------------------------------------------- filesystem */
  const FS_KEY = "lunax_fs_v1";

  function defaultFS() {
    const mk = (content, kind) => ({ type: "file", kind, content, size: content.length, mtime: now() });
    return {
      type: "dir",
      children: {
        home: {
          type: "dir",
          children: {
            luna: {
              type: "dir",
              children: {
                "README.txt": mk(README, "text"),
                "hello.exe": mk(SAMPLES["hello.exe"], "exe"),
                "guess.exe": mk(SAMPLES["guess.exe"], "exe"),
                "fib.exe": mk(SAMPLES["fib.exe"], "exe"),
                "clock.exe": mk(SAMPLES["clock.exe"], "exe"),
                "matrix.exe": mk(SAMPLES["matrix.exe"], "exe"),
                apps: { type: "dir", children: {} },
              },
            },
          },
        },
      },
    };
  }

  const FS = {
    root: null,
    load() {
      try {
        const raw = localStorage.getItem(FS_KEY);
        this.root = raw ? JSON.parse(raw) : defaultFS();
      } catch (e) { this.root = defaultFS(); }
    },
    save() { try { localStorage.setItem(FS_KEY, JSON.stringify(this.root)); } catch (e) {} },
    reset() { this.root = defaultFS(); this.save(); },

    // normalize a path relative to cwd -> array of parts
    resolve(path, cwd) {
      let parts;
      if (path.startsWith("/")) parts = path.split("/");
      else parts = cwd.split("/").concat(path.split("/"));
      const out = [];
      for (const p of parts) {
        if (p === "" || p === ".") continue;
        if (p === "..") { out.pop(); continue; }
        out.push(p);
      }
      return out;
    },
    node(parts) {
      let n = this.root;
      for (const p of parts) {
        if (!n || n.type !== "dir" || !n.children[p]) return null;
        n = n.children[p];
      }
      return n;
    },
    parent(parts) { return this.node(parts.slice(0, -1)); },
    pathStr(parts) { return "/" + parts.join("/"); },

    mkdir(parts) {
      const par = this.parent(parts);
      if (!par || par.type !== "dir") return "no such directory";
      const name = parts[parts.length - 1];
      if (par.children[name]) return name + " already exists";
      par.children[name] = { type: "dir", children: {} };
      this.save(); return null;
    },
    mkdirp(parts) { // create nested dirs, ignoring existing
      let cur = this.root;
      for (const p of parts) {
        if (!cur.children[p]) cur.children[p] = { type: "dir", children: {} };
        if (cur.children[p].type !== "dir") return;
        cur = cur.children[p];
      }
    },
    put(parts, node) { // place an arbitrary node
      const par = this.parent(parts);
      if (!par || par.type !== "dir") return "no such directory";
      par.children[parts[parts.length - 1]] = node;
      this.save(); return null;
    },
    writeFile(parts, content, kind) {
      const par = this.parent(parts);
      if (!par || par.type !== "dir") return "no such directory";
      const name = parts[parts.length - 1];
      kind = kind || (name.endsWith(".exe") ? "exe" : "text");
      par.children[name] = { type: "file", kind, content, size: content.length, mtime: now() };
      this.save(); return null;
    },
    rm(parts) {
      const par = this.parent(parts);
      const name = parts[parts.length - 1];
      if (!par || !par.children[name]) return "no such file";
      delete par.children[name];
      this.save(); return null;
    },
  };

  /* ---------------------------------------------------------------- app state */
  const state = { cwd: "/home/luna", running: false };

  /* ---------------------------------------------------------------- terminal */
  const termOut = $("#term-out");
  const termInput = $("#term-input");
  const COLORS = { black: "#000", red: "var(--err)", green: "var(--ok)", yellow: "var(--warn)",
    blue: "#6ba6ff", magenta: "var(--accent3)", cyan: "var(--accent2)", white: "var(--text)",
    gray: "var(--muted)", grey: "var(--muted)", purple: "var(--accent)" };
  let curColor = null;

  function scrollTerm() { termOut.scrollTop = termOut.scrollHeight; }

  function write(text, cls) {
    const span = document.createElement("span");
    span.className = "l" + (cls ? " " + cls : "");
    if (curColor && !cls) span.style.color = curColor;
    span.textContent = text;
    termOut.appendChild(span);
    scrollTerm();
  }
  function writeHTML(html) {
    const div = document.createElement("div");
    div.className = "l";
    div.innerHTML = html;
    termOut.appendChild(div);
    scrollTerm();
  }
  function nl() { termOut.appendChild(document.createTextNode("\n")); }

  function printPrompt(cmd) {
    const short = state.cwd.replace("/home/luna", "~");
    writeHTML(`<span class="u">luna</span><span class="muted">@</span><span class="accent">lunax</span><span class="muted">:</span><span class="p">${escapeHTML(short)}</span><span class="prompt">$</span> ${escapeHTML(cmd)}`);
  }
  function escapeHTML(s) { return s.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c])); }

  // pending resolver for program INPUT
  let pendingInput = null;

  // the IO bridge handed to the VM
  const vmIO = {
    out: (s) => write(s),
    cls: () => { termOut.innerHTML = ""; },
    color: (name) => { curColor = COLORS[name.toLowerCase()] || null; },
    sleep: (ms) => new Promise((r) => setTimeout(r, Math.min(ms, 60000))),
    isHalted: () => haltFlag,
    inputLine: () => new Promise((resolve) => { pendingInput = resolve; setInputMode("program"); }),
  };
  let haltFlag = false;

  function setInputMode(mode) {
    // mode: 'shell' | 'program'
    termInput.dataset.mode = mode;
    $("#term-ps").textContent = mode === "program" ? "»" : "$";
    termInput.placeholder = mode === "program" ? "program is waiting for input…" : "type a command…";
    termInput.focus();
  }

  async function runExe(parts) {
    const node = FS.node(parts);
    if (!node) { write("lunax: cannot run: no such file\n", "err"); return; }
    if (node.type !== "file") { write("lunax: not a file\n", "err"); return; }
    const name = parts[parts.length - 1];

    if (node.kind === "archive") { openAppViewer(parts, node); write("opening archive viewer…\n", "muted"); return; }
    if (node.kind === "binary") { return runCompat(name, node); }
    if (!name.endsWith(".exe") && node.kind !== "exe") {
      write("lunax: '" + name + "' is not an executable (.exe)\n", "err"); return;
    }

    // execute LunaBASIC
    state.running = true; haltFlag = false;
    $("#stop-btn").classList.remove("hidden");
    const prevColor = curColor;
    writeHTML(`<span class="muted">┌─ launching </span><span class="accent">${escapeHTML(name)}</span><span class="muted"> ───────────────</span>`);
    nl();
    const vm = new LunaVM(vmIO);
    try {
      await vm.run(node.content);
      curColor = null;
      writeHTML(`<span class="muted">└─ ${escapeHTML(name)} finished (exit 0)</span>`);
    } catch (e) {
      curColor = null;
      write("\n", null);
      write("runtime error: " + e.message + "\n", "err");
      writeHTML(`<span class="muted">└─ ${escapeHTML(name)} crashed (exit 1)</span>`);
    } finally {
      nl();
      state.running = false; curColor = prevColor && null;
      $("#stop-btn").classList.add("hidden");
      setInputMode("shell");
    }
  }

  // "compatibility layer" for real uploaded binaries — we can't execute native
  // x86, so we honestly simulate a sandboxed loader.
  async function runCompat(name, node) {
    state.running = true; haltFlag = false;
    $("#stop-btn").classList.remove("hidden");
    let pe = null;
    if (node.content) { try { pe = Arc.inspectPE(Arc.b64ToBytes(node.content)); } catch (e) {} }
    const steps = [
      ["muted", "Lunax Compatibility Layer v1.0"],
      ["muted", "loading PE image: " + name + " (" + fmtSize(node.size) + ")"],
    ];
    if (pe && pe.isPE) {
      steps.push(["accent", "  detected: Windows " + (pe.isDll ? "library (.dll)" : "PC application (.exe)")]);
      steps.push(["accent", "  arch:     " + pe.machine]);
      steps.push(["accent", "  type:     " + pe.subsystem + " · " + pe.sections + " sections"]);
    } else if (pe && pe.dos) {
      steps.push(["accent", "  detected: MS-DOS executable (16-bit)"]);
    }
    steps.push(["muted", "mapping sections .text .data .rsrc ..."]);
    steps.push(["warn", "notice: native code execution is sandboxed on this device"]);
    for (const [c, t] of steps) { write(t + "\n", c); await vmIO.sleep(300); if (haltFlag) break; }
    write("\n", null);
    write("This is a real PC application, so it can't run natively inside a phone browser\n", "accent");
    write("(that needs an x86 CPU). Tap the file in Files → Open to inspect it.\n", "accent");
    write("Lunax can still run its own .exe programs — try  run hello.exe\n", "muted");
    state.running = false;
    $("#stop-btn").classList.add("hidden");
    setInputMode("shell");
  }

  function fmtSize(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1024 / 1024).toFixed(1) + " MB";
  }

  /* ---------------------------------------------------------------- shell commands */
  const commands = {
    help() {
      writeHTML(`<span class="accent">LunaxOS shell — commands</span>
<span class="ok">run &lt;file.exe&gt;</span>   execute a program        <span class="ok">ls</span>            list files
<span class="ok">open &lt;file&gt;</span>       open exe / tar.gz / app   <span class="ok">cat &lt;file&gt;</span>    show contents
<span class="ok">extract &lt;a.tgz&gt;</span>   unpack an archive         <span class="ok">pack &lt;dir&gt;</span>    make a .tar.gz
<span class="ok">edit &lt;file&gt;</span>       open in the editor        <span class="ok">new &lt;f.exe&gt;</span>   create a program
<span class="ok">cd &lt;dir&gt;</span>          change directory          <span class="ok">pwd</span>           print path
<span class="ok">mkdir &lt;dir&gt;</span>       make a directory          <span class="ok">touch &lt;f&gt;</span>     create empty file
<span class="ok">echo &lt;text&gt;</span>       print text                <span class="ok">rm &lt;file&gt;</span>     delete a file
<span class="ok">neofetch</span>          system info               <span class="ok">clear</span>         clear screen
<span class="ok">date</span>              date &amp; time               <span class="ok">reboot</span>        restart LunaxOS
<span class="ok">reset-fs</span>          restore sample files      <span class="ok">help</span>          this help
<span class="muted">Lunax .exe = LunaBASIC programs (run). Real .exe &amp; .tar.gz are PC apps — open to inspect.</span>`);
      nl();
    },
    ls(args) {
      const target = args[0] ? FS.node(FS.resolve(args[0], state.cwd)) : FS.node(FS.resolve(".", state.cwd));
      if (!target || target.type !== "dir") { write("ls: not a directory\n", "err"); return; }
      const names = Object.keys(target.children).sort();
      if (!names.length) { write("(empty)\n", "muted"); return; }
      for (const nm of names) {
        const c = target.children[nm];
        if (c.type === "dir") writeHTML(`<span class="p">${escapeHTML(nm)}/</span>`);
        else if (c.kind === "exe") writeHTML(`<span class="ok">${escapeHTML(nm)}</span>  <span class="muted">${fmtSize(c.size)}</span>`);
        else if (c.kind === "archive") writeHTML(`<span class="warn">${escapeHTML(nm)}</span>  <span class="muted">archive ${fmtSize(c.size)}</span>`);
        else if (c.kind === "binary") writeHTML(`<span class="p">${escapeHTML(nm)}</span>  <span class="muted">app ${fmtSize(c.size)}</span>`);
        else writeHTML(`<span>${escapeHTML(nm)}</span>  <span class="muted">${fmtSize(c.size)}</span>`);
      }
      nl();
    },
    pwd() { write(state.cwd + "\n"); },
    cd(args) {
      if (!args[0]) { state.cwd = "/home/luna"; return; }
      const parts = FS.resolve(args[0], state.cwd);
      const node = FS.node(parts);
      if (!node || node.type !== "dir") { write("cd: no such directory: " + args[0] + "\n", "err"); return; }
      state.cwd = FS.pathStr(parts) || "/";
    },
    cat(args) {
      if (!args[0]) { write("cat: missing file\n", "err"); return; }
      const node = FS.node(FS.resolve(args[0], state.cwd));
      if (!node || node.type !== "file") { write("cat: no such file\n", "err"); return; }
      if (node.kind === "archive") {
        write("archive: " + args[0] + " (" + (node.entries || []).length + " entries)\n", "muted");
        for (const en of (node.entries || [])) write("  " + (en.isDir ? "[dir] " : "") + en.name + (en.isDir ? "" : "  " + fmtSize(en.size)) + "\n", "muted");
        write("use  extract " + args[0] + "  to unpack.\n", "accent"); return;
      }
      if (node.kind === "binary") { write("cat: binary PC app (" + fmtSize(node.size) + ") — try  open " + args[0] + "\n", "muted"); return; }
      write(node.content + (node.content.endsWith("\n") ? "" : "\n"));
    },
    echo(args, raw) { write(raw + "\n"); },
    mkdir(args) {
      if (!args[0]) { write("mkdir: missing name\n", "err"); return; }
      const err = FS.mkdir(FS.resolve(args[0], state.cwd));
      if (err) write("mkdir: " + err + "\n", "err");
    },
    touch(args) {
      if (!args[0]) { write("touch: missing name\n", "err"); return; }
      const err = FS.writeFile(FS.resolve(args[0], state.cwd), "");
      if (err) write("touch: " + err + "\n", "err");
    },
    rm(args) {
      if (!args[0]) { write("rm: missing file\n", "err"); return; }
      const err = FS.rm(FS.resolve(args[0], state.cwd));
      if (err) write("rm: " + err + "\n", "err"); else refreshFiles();
    },
    clear() { termOut.innerHTML = ""; },
    whoami() { write("luna\n"); },
    date() { write(new Date().toString() + "\n"); },
    neofetch() {
      const fc = countExe();
      writeHTML(`<span class="accent">        .-"-.       </span> <span class="u">luna</span><span class="muted">@</span><span class="accent">lunax</span>
<span class="accent">      /  .-. \\      </span> <span class="muted">---------------</span>
<span class="accent">     |  /   \\ |     </span> OS:      <span class="ok">LunaxOS 1.0 "Selene"</span>
<span class="accent">     | |     ||     </span> Kernel:  Luna Virtual Kernel
<span class="accent">     |  \\   / |     </span> Shell:   lunash
<span class="accent">      \\  '-' /      </span> Runtime: LunaBASIC
<span class="accent">       '-.-'       </span> Programs:<span class="ok"> ${fc} .exe</span>
<span class="accent">                    </span> Host:    your phone`);
      nl();
    },
    reboot() { location.reload(); },
    "reset-fs"() { FS.reset(); refreshFiles(); write("filesystem restored to defaults.\n", "ok"); },
    edit(args) {
      if (!args[0]) { write("edit: missing file\n", "err"); return; }
      const parts = FS.resolve(args[0], state.cwd);
      const node = FS.node(parts);
      if (node && node.type === "dir") { write("edit: is a directory\n", "err"); return; }
      openEditor(parts, node ? node.content : "");
      write("opening editor…\n", "muted");
    },
    new(args) {
      if (!args[0]) { write("new: usage: new myapp.exe\n", "err"); return; }
      let name = args[0]; if (!/\.\w+$/.test(name)) name += ".exe";
      const parts = FS.resolve(name, state.cwd);
      const tmpl = name.endsWith(".exe") ? 'REM ' + name + '\nPRINT "Hello from ' + name + '"\n' : "";
      openEditor(parts, tmpl);
      write("new program: " + name + " — edit and Save.\n", "muted");
    },
    open(args) {
      if (!args[0]) { write("open: usage: open <file>\n", "err"); return; }
      const parts = FS.resolve(args[0], state.cwd);
      const node = FS.node(parts);
      if (!node) { write("open: no such file: " + args[0] + "\n", "err"); return; }
      if (node.type === "dir") { state.cwd = FS.pathStr(parts) || "/"; return; }
      openAppViewer(parts, node);
      write("opening " + args[0] + "…\n", "muted");
    },
    extract(args) {
      if (!args[0]) { write("extract: usage: extract <archive.tar.gz>\n", "err"); return; }
      const parts = FS.resolve(args[0], state.cwd);
      const node = FS.node(parts);
      if (!node || node.kind !== "archive") { write("extract: not an archive: " + args[0] + "\n", "err"); return; }
      write("extracting " + args[0] + "…\n", "muted");
      extractArchive(parts, node);
    },
    pack(args) {
      if (!args[0]) { write("pack: usage: pack <folder>\n", "err"); return; }
      const parts = FS.resolve(args[0], state.cwd);
      const node = FS.node(parts);
      if (!node || node.type !== "dir") { write("pack: not a folder: " + args[0] + "\n", "err"); return; }
      write("packing " + args[0] + " → " + args[0] + ".tar.gz…\n", "muted");
      packFolder(parts);
    },
    run(args) { /* handled specially (async) */ },
  };

  function countExe() {
    let n = 0;
    (function walk(node) {
      if (node.type === "dir") { for (const k in node.children) walk(node.children[k]); }
      else if (node.kind === "exe") n++;
    })(FS.root);
    return n;
  }

  async function handleCommand(raw) {
    const line = raw.trim();
    if (line === "") return;
    printPrompt(raw);
    const parts = line.split(/\s+/);
    const cmd = parts[0].toLowerCase();
    const args = parts.slice(1);
    const rest = line.slice(parts[0].length).trim();

    if (cmd === "run" || cmd === "./" + args[0] || cmd.startsWith("./")) {
      let fileArg = cmd === "run" ? args[0] : cmd.slice(2);
      if (!fileArg) { write("run: usage: run <file.exe>\n", "err"); return; }
      await runExe(FS.resolve(fileArg, state.cwd));
      return;
    }
    if (commands[cmd]) { commands[cmd](args, rest); refreshFiles(); return; }
    write("lunash: command not found: " + cmd + "  (try 'help')\n", "err");
  }

  // terminal input handling
  termInput.addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    e.preventDefault();
    const val = termInput.value;
    const mode = termInput.dataset.mode || "shell";
    if (mode === "program") {
      write(val + "\n");
      termInput.value = "";
      const r = pendingInput; pendingInput = null;
      if (r) r(val);
      return;
    }
    termInput.value = "";
    if (val.trim()) { history.push(val); histIdx = history.length; }
    handleCommand(val);
  });
  // command history
  const history = []; let histIdx = 0;
  termInput.addEventListener("keydown", (e) => {
    if ((termInput.dataset.mode || "shell") !== "shell") return;
    if (e.key === "ArrowUp") { if (histIdx > 0) { histIdx--; termInput.value = history[histIdx]; } e.preventDefault(); }
    if (e.key === "ArrowDown") { if (histIdx < history.length - 1) { histIdx++; termInput.value = history[histIdx]; } else { histIdx = history.length; termInput.value = ""; } e.preventDefault(); }
  });

  $("#stop-btn").addEventListener("click", () => { haltFlag = true; if (pendingInput) { const r = pendingInput; pendingInput = null; r(""); } });

  // quick key bar
  $$(".term-keys button").forEach((b) => b.addEventListener("click", () => {
    const ins = b.dataset.ins;
    if (ins === "\n") { const ev = new KeyboardEvent("keydown", { key: "Enter" }); termInput.dispatchEvent(ev); return; }
    termInput.value += ins; termInput.focus();
  }));

  /* ---------------------------------------------------------------- files app */
  const fileList = $("#file-list");
  function refreshFiles() {
    $("#file-path").textContent = state.cwd.replace("/home/luna", "~");
    const node = FS.node(FS.resolve(".", state.cwd));
    fileList.innerHTML = "";
    if (!node || node.type !== "dir") return;
    const names = Object.keys(node.children).sort((a, b) => {
      const da = node.children[a].type === "dir", db = node.children[b].type === "dir";
      if (da !== db) return da ? -1 : 1;
      return a.localeCompare(b);
    });
    if (state.cwd !== "/") names.unshift("..");
    if (!names.length) { fileList.innerHTML = '<div class="empty">This folder is empty.<br>Tap “＋ New” to create a program.</div>'; return; }
    for (const nm of names) {
      const isUp = nm === "..";
      const c = isUp ? { type: "dir" } : node.children[nm];
      const isDir = c.type === "dir";
      const isLunaExe = !isUp && c.kind === "exe";
      const isArchive = !isUp && c.kind === "archive";
      const isBinary = !isUp && c.kind === "binary";
      const row = document.createElement("div");
      row.className = "fileitem" + (isLunaExe ? " exe" : "");
      const glyph = isUp ? "↩" : isDir ? "📁" : isLunaExe ? "🌙" : isArchive ? "📦" : isBinary ? "🪟" : "📄";
      row.innerHTML = `<div class="fi-glyph">${glyph}</div>
        <div class="fi-name">${escapeHTML(nm)}${isDir && !isUp ? "/" : ""}</div>
        ${c.type === "file" ? `<div class="fi-meta">${fmtSize(c.size)}</div>` : ""}`;
      const actions = document.createElement("div");
      actions.style.display = "flex"; actions.style.gap = "6px"; actions.style.alignItems = "center";
      if (isLunaExe) {
        const rb = document.createElement("button"); rb.className = "fi-run"; rb.textContent = "▶ RUN";
        rb.addEventListener("click", (e) => { e.stopPropagation(); openWindow("term"); runExe(FS.resolve(nm, state.cwd)); });
        actions.appendChild(rb);
      } else if (isArchive || isBinary) {
        const ob = document.createElement("button"); ob.className = "fi-open"; ob.textContent = "⤢ OPEN";
        ob.addEventListener("click", (e) => { e.stopPropagation(); openAppViewer(FS.resolve(nm, state.cwd), c); });
        actions.appendChild(ob);
      } else if (isDir && !isUp) {
        const pb = document.createElement("button"); pb.className = "btn"; pb.textContent = "📦"; pb.title = "Pack to .tar.gz";
        pb.style.padding = "6px 9px";
        pb.addEventListener("click", (e) => { e.stopPropagation(); packFolder(FS.resolve(nm, state.cwd)); });
        actions.appendChild(pb);
      }
      if (!isUp && c.type === "file") {
        const db = document.createElement("button"); db.className = "btn"; db.textContent = "🗑";
        db.style.padding = "6px 9px";
        db.addEventListener("click", (e) => { e.stopPropagation(); if (confirm("Delete " + nm + "?")) { FS.rm(FS.resolve(nm, state.cwd)); refreshFiles(); } });
        actions.appendChild(db);
      }
      row.appendChild(actions);
      row.addEventListener("click", () => {
        if (isDir) { state.cwd = FS.pathStr(FS.resolve(nm, state.cwd)) || "/"; refreshFiles(); }
        else if (isArchive || isBinary) { openAppViewer(FS.resolve(nm, state.cwd), c); }
        else { openEditor(FS.resolve(nm, state.cwd), c.content); }
      });
      fileList.appendChild(row);
    }
  }

  $("#btn-new").addEventListener("click", () => {
    let name = prompt("New program name:", "myapp.exe");
    if (!name) return;
    if (!/\.\w+$/.test(name)) name += ".exe";
    const parts = FS.resolve(name, state.cwd);
    openEditor(parts, name.endsWith(".exe") ? 'REM ' + name + '\nPRINT "Hello from ' + name + '"\n' : "");
  });
  $("#btn-mkdir").addEventListener("click", () => {
    const name = prompt("New folder name:", "folder");
    if (!name) return;
    FS.mkdir(FS.resolve(name, state.cwd)); refreshFiles();
  });
  $("#btn-upload").addEventListener("click", () => $("#file-input").click());
  $("#file-input").addEventListener("change", async (e) => {
    const f = e.target.files[0]; if (!f) return;
    try { await importFile(f); } catch (err) { toast("Import failed: " + err.message); }
    e.target.value = "";
  });

  const RAW_LIMIT = 1_500_000; // keep bytes for files up to ~1.5MB (localStorage)
  const TEXT_EXT = /\.(txt|bas|lxe|md|js|json|log|csv|ini|cfg|conf|html|css|xml|sh|c|h|py|yml|yaml)$/i;

  function hasNullBytes(buf) { const n = Math.min(buf.length, 512); for (let i = 0; i < n; i++) if (buf[i] === 0) return true; return false; }

  async function importFile(f) {
    const buf = new Uint8Array(await f.arrayBuffer());
    const name = f.name;
    const parts = FS.resolve(name, state.cwd);

    // --- archives (tar / gz / tar.gz / tgz) ---
    if (Arc.isArchiveName(name)) {
      if (!Arc.supported) { toast("This browser can't open .gz archives"); }
      let entries = [];
      try { entries = await Arc.listArchive(name, buf); }
      catch (err) { toast("Couldn't read archive: " + err.message); }
      FS.put(parts, {
        type: "file", kind: "archive", size: f.size, mtime: now(),
        content: buf.length <= RAW_LIMIT ? Arc.bytesToB64(buf) : "",
        entries: entries.map((en) => ({ name: en.name, size: en.size, isDir: !!en.isDir })),
      });
      refreshFiles(); toast("Imported archive " + name + " (" + entries.length + " entries)");
      openAppViewer(parts, FS.node(parts));
      return;
    }

    const pe = Arc.inspectPE(buf);
    const looksBinary = pe.isPE || pe.dos || hasNullBytes(buf);
    const isExeName = name.toLowerCase().endsWith(".exe");

    // --- real Windows / native binary (.exe or other) ---
    if (looksBinary && !TEXT_EXT.test(name)) {
      FS.put(parts, {
        type: "file", kind: "binary", size: f.size, mtime: now(),
        content: buf.length <= RAW_LIMIT ? Arc.bytesToB64(buf) : "",
      });
      refreshFiles(); toast("Imported " + name);
      openAppViewer(parts, FS.node(parts));
      return;
    }

    // --- text: LunaBASIC .exe or a plain text file ---
    const text = new TextDecoder().decode(buf);
    FS.writeFile(parts, text, isExeName ? "exe" : "text");
    refreshFiles(); toast("Imported " + name);
  }

  /* --------- archive extraction & packing --------- */
  async function extractArchive(parts, node) {
    const name = parts[parts.length - 1];
    if (!node.content) { toast("Archive too large to keep — re-import to extract"); return; }
    let entries;
    try { entries = await Arc.listArchive(name, Arc.b64ToBytes(node.content)); }
    catch (err) { toast("Extract failed: " + err.message); return; }
    const base = name.replace(/\.(tar\.gz|tgz|tar|gz)$/i, "");
    FS.mkdirp(FS.resolve(base, state.cwd));
    let count = 0;
    for (const en of entries) {
      const p = FS.resolve(base + "/" + en.name, state.cwd);
      if (en.isDir) { FS.mkdirp(p); continue; }
      FS.mkdirp(p.slice(0, -1));
      const lname = en.name.toLowerCase();
      const peE = Arc.inspectPE(en.data);
      if (lname.endsWith(".exe") && (peE.isPE || peE.dos)) {
        FS.put(p, { type: "file", kind: "binary", size: en.size, mtime: now(), content: en.data.length <= RAW_LIMIT ? Arc.bytesToB64(en.data) : "" });
      } else if (peE.isPE || peE.dos || hasNullBytes(en.data)) {
        FS.put(p, { type: "file", kind: "binary", size: en.size, mtime: now(), content: en.data.length <= RAW_LIMIT ? Arc.bytesToB64(en.data) : "" });
      } else {
        const txt = new TextDecoder().decode(en.data);
        FS.put(p, { type: "file", kind: lname.endsWith(".exe") ? "exe" : "text", content: txt, size: en.size, mtime: now() });
      }
      count++;
    }
    FS.save(); refreshFiles();
    toast("Extracted " + count + " files → " + base + "/");
    closeWindow("app");
    openWindow("files");
  }

  async function packFolder(parts) {
    const node = FS.node(parts);
    if (!node || node.type !== "dir") { toast("Not a folder"); return; }
    if (!Arc.supported) { toast("This browser can't create .gz archives"); return; }
    const files = [];
    (function walk(n, prefix) {
      for (const k of Object.keys(n.children)) {
        const c = n.children[k];
        const path = prefix ? prefix + "/" + k : k;
        if (c.type === "dir") { walk(c, path); }
        else {
          let data;
          if (c.kind === "text" || c.kind === "exe") data = new TextEncoder().encode(c.content || "");
          else if (c.content) data = Arc.b64ToBytes(c.content);
          else data = new Uint8Array(0);
          files.push({ name: path, data });
        }
      }
    })(node, "");
    if (!files.length) { toast("Folder is empty"); return; }
    const gz = await Arc.gzip(Arc.tar(files));
    const arcName = parts[parts.length - 1] + ".tar.gz";
    FS.put(FS.resolve(arcName, state.cwd), {
      type: "file", kind: "archive", size: gz.length, mtime: now(),
      content: gz.length <= RAW_LIMIT ? Arc.bytesToB64(gz) : "",
      entries: files.map((f) => ({ name: f.name, size: f.data.length, isDir: false })),
    });
    refreshFiles(); toast("Packed → " + arcName);
  }

  /* --------- App / archive viewer --------- */
  function el(html) { const d = document.createElement("div"); d.innerHTML = html.trim(); return d.firstElementChild; }

  function openAppViewer(parts, node) {
    const name = parts[parts.length - 1];
    const host = $("#app-view");
    $("#app-title").textContent = name;
    host.innerHTML = "";

    if (node.kind === "archive") {
      const entries = node.entries || [];
      host.appendChild(el(`<div class="av-head"><div class="av-glyph">📦</div><div><div class="av-name">${escapeHTML(name)}</div><div class="av-meta">${Arc.archiveKind(name) || "archive"} · ${fmtSize(node.size)} · ${entries.length} entries</div></div></div>`));
      const actions = document.createElement("div"); actions.className = "av-actions";
      const ext = document.createElement("button"); ext.className = "btn primary"; ext.textContent = "⤓ Extract all";
      ext.addEventListener("click", () => extractArchive(parts, node));
      actions.appendChild(ext);
      host.appendChild(actions);
      const list = document.createElement("div"); list.className = "av-list";
      if (!entries.length) list.innerHTML = '<div class="empty">No readable entries.</div>';
      for (const en of entries) {
        const isExe = /\.exe$/i.test(en.name);
        list.appendChild(el(`<div class="av-item"><span>${en.isDir ? "📁" : isExe ? "🪟" : "📄"}</span><span class="av-fn">${escapeHTML(en.name)}</span><span class="av-sz">${en.isDir ? "" : fmtSize(en.size)}</span></div>`));
      }
      host.appendChild(list);
      host.appendChild(el(`<div class="av-note">Extract to unpack this archive into a folder on your virtual disk. A real Windows <code>.exe</code> inside is a PC application — Lunax can open and inspect it, though native code needs an x86 CPU to actually execute.</div>`));
      openWindow("app");
      return;
    }

    if (node.kind === "binary") {
      let pe = { isPE: false };
      if (node.content) { try { pe = Arc.inspectPE(Arc.b64ToBytes(node.content)); } catch (e) {} }
      host.appendChild(el(`<div class="av-head"><div class="av-glyph">${pe.isPE ? "🪟" : "📦"}</div><div><div class="av-name">${escapeHTML(name)}</div><div class="av-meta">${fmtSize(node.size)}</div></div></div>`));
      const info = document.createElement("div"); info.className = "av-info";
      const rows = [];
      if (pe.isPE) {
        rows.push(["Type", "Windows " + (pe.isDll ? "library (.dll)" : "PC application (.exe)")]);
        rows.push(["Architecture", pe.machine]);
        rows.push(["Subsystem", pe.subsystem]);
        rows.push(["Word size", pe.bits + "-bit"]);
        rows.push(["Sections", String(pe.sections)]);
      } else if (pe.dos) { rows.push(["Type", "MS-DOS executable (16-bit)"]); }
      else { rows.push(["Type", "Binary file"]); if (!node.content) rows.push(["Note", "contents not stored (too large)"]); }
      for (const [k, v] of rows) info.appendChild(el(`<div class="av-row"><span class="av-k">${escapeHTML(k)}</span><span class="av-v">${escapeHTML(v)}</span></div>`));
      host.appendChild(info);
      const actions = document.createElement("div"); actions.className = "av-actions";
      const runb = document.createElement("button"); runb.className = "btn primary"; runb.textContent = "▶ Run (compat layer)";
      runb.addEventListener("click", () => { openWindow("term"); runCompat(name, node); });
      actions.appendChild(runb);
      host.appendChild(actions);
      host.appendChild(el(`<div class="av-note">${pe.isPE ? "This is a genuine Windows PC application." : "This looks like a native binary."} It can't execute inside a phone browser (no x86 CPU) — but Lunax opened it and read its headers. Lunax's own <code>.exe</code> programs run fine; try the samples.</div>`));
      openWindow("app");
      return;
    }

    // text / lunabasic exe → editor
    openEditor(parts, node.content || "");
  }

  /* ---------------------------------------------------------------- editor */
  let editorTarget = null;
  function openEditor(parts, content) {
    editorTarget = parts;
    $("#edit-name").value = parts[parts.length - 1];
    $("#editor").value = content || "";
    openWindow("edit");
    setTimeout(() => $("#editor").focus(), 60);
  }
  $("#btn-save").addEventListener("click", () => {
    const name = $("#edit-name").value.trim();
    if (!name) { toast("Name cannot be empty"); return; }
    const parts = FS.resolve(name, state.cwd);
    // if renamed, remove old
    if (editorTarget && editorTarget[editorTarget.length - 1] !== name) FS.rm(editorTarget);
    FS.writeFile(parts, $("#editor").value);
    editorTarget = parts;
    refreshFiles(); toast("Saved " + name);
  });
  $("#btn-save-run").addEventListener("click", () => {
    $("#btn-save").click();
    const name = $("#edit-name").value.trim();
    openWindow("term");
    runExe(FS.resolve(name, state.cwd));
  });

  /* ---------------------------------------------------------------- windows */
  const WINS = ["term", "files", "edit", "about", "app"];
  function openWindow(id) {
    WINS.forEach((w) => {
      const el = $("#win-" + w);
      const on = w === id;
      el.classList.toggle("open", on || (el.classList.contains("open") && window.innerWidth >= 780));
    });
    // on mobile, only one window visible
    if (window.innerWidth < 780) WINS.forEach((w) => $("#win-" + w).classList.toggle("open", w === id));
    $("#win-" + id).classList.add("open");
    // z / active
    WINS.forEach((w) => $("#win-" + w).classList.toggle("active", w === id));
    const el = $("#win-" + id); el.style.zIndex = ++zTop;
    // taskbar highlight
    $$(".taskbar button").forEach((b) => b.classList.toggle("active", b.dataset.win === id));
    if (id === "files") refreshFiles();
    if (id === "term") setTimeout(() => termInput.focus(), 60);
  }
  let zTop = 10;
  function closeWindow(id) {
    $("#win-" + id).classList.remove("open", "active");
    $$(".taskbar button").forEach((b) => { if (b.dataset.win === id) b.classList.remove("active"); });
  }

  $$("[data-open]").forEach((el) => el.addEventListener("click", () => openWindow(el.dataset.open)));
  $$(".taskbar button").forEach((b) => b.addEventListener("click", () => {
    const id = b.dataset.win;
    if ($("#win-" + id).classList.contains("open") && b.classList.contains("active") && window.innerWidth >= 780) closeWindow(id);
    else openWindow(id);
  }));
  $$(".titlebar .close").forEach((b) => b.addEventListener("click", () => closeWindow(b.dataset.win)));

  // make desktop windows draggable by titlebar (desktop only)
  $$(".window").forEach((win) => {
    const bar = win.querySelector(".titlebar");
    let sx, sy, ox, oy, drag = false;
    bar.addEventListener("pointerdown", (e) => {
      if (window.innerWidth < 780) return;
      if (e.target.closest("button")) return;
      drag = true; sx = e.clientX; sy = e.clientY;
      ox = win.offsetLeft; oy = win.offsetTop; bar.setPointerCapture(e.pointerId);
      win.style.zIndex = ++zTop;
    });
    bar.addEventListener("pointermove", (e) => {
      if (!drag) return;
      win.style.left = Math.max(0, ox + e.clientX - sx) + "px";
      win.style.top = Math.max(34, oy + e.clientY - sy) + "px";
    });
    bar.addEventListener("pointerup", () => { drag = false; });
  });

  /* ---------------------------------------------------------------- misc UI */
  function toast(msg) {
    const t = $("#toast"); t.textContent = msg; t.classList.add("show");
    clearTimeout(t._t); t._t = setTimeout(() => t.classList.remove("show"), 1800);
  }
  function tickClock() {
    const d = new Date();
    $("#clock").textContent = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  setInterval(tickClock, 1000);

  /* ---------------------------------------------------------------- boot */
  function boot() {
    FS.load();
    tickClock();
    setInputMode("shell");
    // intro text in terminal
    writeHTML(`<span class="accent">LunaxOS 1.0 "Selene"</span>  <span class="muted">— virtual environment</span>`);
    nl();
    write("Type ", "muted"); write("help", "ok"); write(" for commands, or ", "muted");
    write("run hello.exe", "ok"); write(" to launch your first program.\n", "muted");
    nl();

    const logs = ["init luna-kernel", "mount /dev/moon", "load LunaBASIC runtime", "spawn lunash", "ready"];
    let li = 0;
    const bl = $("#bootlog");
    const t = setInterval(() => { bl.textContent = "[ ok ] " + (logs[li] || "ready"); li++; }, 400);
    setTimeout(() => {
      clearInterval(t);
      $("#boot").classList.add("hide");
      openWindow("term");
    }, 2300);
  }

  document.addEventListener("DOMContentLoaded", boot);
})();
