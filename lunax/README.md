# 🌙 Lunax — a virtual environment for your phone

> スマホでは `.exe` が実行できなくてつまらない… を解決するアプリ。
> Phones can't run native `.exe` files — so Lunax gives you its **own** OS,
> console, and executable format that really run, right in the browser.

Lunax is a self‑contained web app (no server, no build step). Open
`index.html` on any phone or desktop browser and you get a tiny virtual
OS — **LunaxOS** — with a desktop, a real terminal, a file manager, and a
code editor.

You can't run native Windows binaries inside a phone browser (that needs an
x86 CPU). So Lunax ships **LunaBASIC**: small `.exe` programs that genuinely
execute on a built‑in interpreter. Pick one and run it — with a live console.

## Features

- 🖥️ **Terminal (console)** — a real shell: `run`, `ls`, `cd`, `cat`,
  `edit`, `mkdir`, `rm`, `neofetch`, command history, quick‑key bar for phones.
- ▶️ **Select & run `.exe`** — tap the green **RUN** button in Files, or
  `run hello.exe` in the console. Programs read input and print output live.
- 📁 **Virtual filesystem** — folders and files persist in the browser
  (`localStorage`). Create, edit, delete, import files from your device.
- 📝 **Editor** — write LunaBASIC, save as `*.exe`, hit ▶ Run.
- 📦 **Compatibility layer** — import a *real* binary and Lunax honestly
  shows a sandboxed loader (native x86 can't run on a phone).
- 📱 Designed mobile‑first: touch targets, on‑screen keys, safe‑area aware.

## Run it

Just open `lunax/index.html` in a browser. On a phone, "Add to Home Screen"
for a fullscreen app feel. No install, no network needed.

Bundled sample programs: `hello.exe`, `guess.exe` (number game),
`fib.exe`, `clock.exe`, `matrix.exe`.

## LunaBASIC quick reference

```basic
REM comment (or use ')
PRINT "text", expr        ' comma = space, ; = no newline
INPUT "prompt"; var$      ' name$ = string, name = number
LET x = 5                 ' LET optional:  x = 5
IF x > 3 THEN PRINT "big" ELSE PRINT "small"
FOR i = 1 TO 10 STEP 2 ... NEXT i
WHILE cond ... WEND
GOTO label   /  label:    /  GOSUB label ... RETURN
COLOR cyan                ' red green yellow blue magenta cyan white ...
SLEEP 500                 ' milliseconds
CLS                       ' clear screen
END
```

Operators: `+ - * /`, `MOD`, `= <> < > <= >=`, `AND OR NOT`.
Functions: `RND INT ABS SQR MIN MAX POW`,
`LEN LEFT$ RIGHT$ MID$ UPPER$ LOWER$ TRIM$ CHR$ ASC STR$ VAL SPACE$ TIME$ DATE$`.

### Example

```basic
COLOR cyan
INPUT "Your name? "; n$
COLOR green
FOR i = 1 TO 3
  PRINT "Hi " + n$ + " (" + STR$(i) + ")"
NEXT i
```

## Files

| file | purpose |
|------|---------|
| `index.html` | desktop, windows, terminal, editor markup |
| `styles.css` | moonlit theme, responsive layout |
| `lunabasic.js` | the LunaBASIC interpreter (VM) |
| `app.js` | filesystem, shell commands, file manager, UI wiring |
