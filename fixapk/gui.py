"""A tiny Tkinter front end: pick (or drag in) an old APK, get a fixed one.

Drag-and-drop works when the optional ``tkinterdnd2`` package is installed;
otherwise the "APKを選ぶ" button is always available.  Everything runs the
same deterministic :func:`fixapk.fix_apk` pipeline as the CLI — no AI, no
network.
"""

from __future__ import annotations

import queue
import threading

from . import apk, fix_apk, signer


def _run_gui():
    import tkinter as tk
    from tkinter import filedialog, scrolledtext

    try:
        from tkinterdnd2 import DND_FILES, TkinterDnD

        root = TkinterDnD.Tk()
        dnd_ok = True
    except Exception:
        root = tk.Tk()
        dnd_ok = False

    root.title("fixapk — 古いAPKを最新化")
    root.geometry("640x480")

    log_q: "queue.Queue[str]" = queue.Queue()

    def log(msg: str):
        log_q.put(msg)

    drop_text = (
        "ここに古いAPKをドラッグ&ドロップ"
        if dnd_ok
        else "下のボタンから古いAPKを選んでください"
    )
    drop = tk.Label(
        root,
        text=drop_text,
        relief="ridge",
        borderwidth=2,
        height=5,
        bg="#f0f0f0",
    )
    drop.pack(fill="x", padx=12, pady=12)

    out = scrolledtext.ScrolledText(root, height=18, state="disabled")
    out.pack(fill="both", expand=True, padx=12, pady=(0, 12))

    def append(text: str):
        out.configure(state="normal")
        out.insert("end", text + "\n")
        out.see("end")
        out.configure(state="disabled")

    def poll_queue():
        try:
            while True:
                append(log_q.get_nowait())
        except queue.Empty:
            pass
        root.after(120, poll_queue)

    def convert(path: str):
        def worker():
            log(f"\n=== {path} ===")
            try:
                result = fix_apk(path)
            except (apk.ApkError, signer.SignError) as exc:
                log(f"失敗: {exc}")
                return
            log(result.summary())
            if result.sign.verified:
                log("✅ 完成! 出力APKを端末に転送してインストールしてください。")

        threading.Thread(target=worker, daemon=True).start()

    def choose():
        paths = filedialog.askopenfilenames(
            title="古いAPKを選択", filetypes=[("APK", "*.apk"), ("すべて", "*.*")]
        )
        for p in paths:
            convert(p)

    btn = tk.Button(drop, text="APKを選ぶ…", command=choose)
    btn.pack(pady=8)

    if dnd_ok:
        def on_drop(event):
            for p in root.tk.splitlist(event.data):
                convert(p)

        drop.drop_target_register(DND_FILES)
        drop.dnd_bind("<<Drop>>", on_drop)

    poll_queue()
    root.mainloop()
    return 0


def main() -> int:
    try:
        return _run_gui()
    except ImportError:
        print(
            "tkinter が見つかりません。GUI を使うには python3-tk を入れてください\n"
            "  (例: sudo apt install python3-tk)\n"
            "GUI 無しでも次のように使えます:\n"
            "  python -m fixapk 古いアプリ.apk"
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
