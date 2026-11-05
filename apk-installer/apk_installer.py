#!/usr/bin/env python3
"""APK Installer (Windows) — PC から USB 接続の Android 端末へ APK を入れるツール.

・内部で Android SDK Platform-Tools の adb.exe を使う。
・adb が無い場合は Google 公式の platform-tools を自動ダウンロードする
  (保存先: %LOCALAPPDATA%\\APKInstaller\\platform-tools)。
・複数 APK の一括インストール、上書き / ダウングレード / 権限自動付与オプションに対応。
・コマンドライン引数に APK を渡して起動できる (エクスプローラーの「送る」「開く」用)。

GUI 部分は tkinter のみ使用。ロジック部分は tkinter 無しでもテスト可能。
"""
from __future__ import annotations

import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

APP_TITLE = "APK Installer"
ADB_ZIP_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
INSTALL_TIMEOUT_SEC = 900
# Windows でコンソール窓を出さない (Linux など他 OS では 0 = 無効)
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


# ---------------------------------------------------------------------------
# パス / adb 検出
# ---------------------------------------------------------------------------
def app_dir() -> Path:
    """exe ならその場所、スクリプトならそのファイルの場所。"""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def tools_dir() -> Path:
    """adb を自動ダウンロードする先。Program Files など書き込み不可の場所を避ける。"""
    local = os.environ.get("LOCALAPPDATA")
    root = Path(local) if local else Path.home()
    return root / "APKInstaller"


def adb_candidates(base: Path) -> list[Path]:
    cands = [
        base / "platform-tools" / "adb.exe",
        base / "adb.exe",
        tools_dir() / "platform-tools" / "adb.exe",
    ]
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value:
            cands.append(Path(value) / "platform-tools" / "adb.exe")
    local = os.environ.get("LOCALAPPDATA")
    if local:
        cands.append(Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
    return cands


def find_adb(base: Path | None = None) -> str | None:
    """adb の実行パスを返す。見つからなければ None。"""
    for candidate in adb_candidates(base or app_dir()):
        if candidate.is_file():
            return str(candidate)
    return shutil.which("adb")


def download_platform_tools(log: Callable[[str], None]) -> str:
    """Google 公式の platform-tools を取得して adb のパスを返す。"""
    dest = tools_dir()
    dest.mkdir(parents=True, exist_ok=True)
    log(f"adb (Platform-Tools) をダウンロード中: {ADB_ZIP_URL}")
    with tempfile.TemporaryDirectory() as tmp:
        zip_path = Path(tmp) / "platform-tools.zip"
        with urllib.request.urlopen(ADB_ZIP_URL, timeout=120) as response, open(zip_path, "wb") as out:
            shutil.copyfileobj(response, out)
        log("展開中...")
        extract_zip_safely(zip_path, dest)
    adb = dest / "platform-tools" / "adb.exe"
    if not adb.is_file():
        raise RuntimeError("展開後に adb.exe が見つかりませんでした")
    return str(adb)


def extract_zip_safely(zip_path: Path, dest: Path) -> None:
    """zip 内に「../」などでフォルダ外へ書き出すパスが無いか確認してから展開する。"""
    root = dest.resolve()
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            target = (root / member.filename).resolve()
            if not target.is_relative_to(root):
                raise RuntimeError(f"不正なパスを含む zip です: {member.filename}")
        archive.extractall(root)


# ---------------------------------------------------------------------------
# adb 呼び出し / 結果解析 (テスト対象)
# ---------------------------------------------------------------------------
def run_cmd(args: list[str], timeout: float = INSTALL_TIMEOUT_SEC) -> tuple[int, str]:
    proc = subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        creationflags=CREATE_NO_WINDOW,
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


@dataclass(frozen=True)
class Device:
    serial: str
    state: str  # device / unauthorized / offline / recovery など
    model: str = ""

    @property
    def ready(self) -> bool:
        return self.state == "device"

    @property
    def label(self) -> str:
        name = self.model or self.serial
        status = "" if self.ready else f"  [{self.state_message}]"
        return f"{name} ({self.serial}){status}"

    @property
    def state_message(self) -> str:
        if self.state == "unauthorized":
            return "端末で USB デバッグを許可してください"
        if self.state == "offline":
            return "オフライン: USB を挿し直してください"
        return self.state


def parse_devices(output: str) -> list[Device]:
    """`adb devices -l` の出力を解析する。"""
    devices: list[Device] = []
    for raw in output.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        model_match = re.search(r"model:(\S+)", line)
        model = model_match.group(1).replace("_", " ") if model_match else ""
        devices.append(Device(serial=parts[0], state=parts[1], model=model))
    return devices


@dataclass(frozen=True)
class InstallOptions:
    replace: bool = True       # -r 既存アプリを上書き
    downgrade: bool = False    # -d 古いバージョンへの変更を許可
    grant_all: bool = False    # -g 実行時権限を自動付与


def build_install_cmd(adb: str, serial: str, apk: Path, opts: InstallOptions) -> list[str]:
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd.append("install")
    if opts.replace:
        cmd.append("-r")
    if opts.downgrade:
        cmd.append("-d")
    if opts.grant_all:
        cmd.append("-g")
    cmd.append(str(apk))
    return cmd


def interpret_install_result(returncode: int, output: str) -> tuple[bool, str]:
    """adb install の結果から (成功か, 表示用メッセージ) を決める。"""
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if returncode == 0 and "Success" in lines:
        return True, "成功"
    failure = next(
        (line for line in reversed(lines) if line.startswith(("Failure", "adb:", "error"))),
        None,
    )
    if failure:
        return False, failure
    return False, lines[-1] if lines else f"終了コード {returncode}"


# 失敗コードごとの対処ヒント (日本語)
FAILURE_HINTS: dict[str, str] = {
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE": "署名が異なるため上書きできません。端末側でこのアプリを先にアンインストールしてください。",
    "INSTALL_FAILED_VERSION_DOWNGRADE": "端末の方が新しいバージョンです。「ダウングレードを許可」をオンにしてください。",
    "INSTALL_FAILED_INSUFFICIENT_STORAGE": "端末の空き容量が不足しています。",
    "INSTALL_FAILED_USER_RESTRICTED": "端末側でインストールが制限されています。USB経由のインストールを許可するか、端末の確認画面で許可してください。",
    "INSTALL_FAILED_NO_MATCHING_ABIS": "この端末の CPU に対応していない APK です。",
    "INSTALL_FAILED_OLDER_SDK": "この APK は端末の Android バージョンより新しい版が必要です。",
    "INSTALL_PARSE_FAILED": "APK が壊れているか、対応していない形式です (XAPK / AAB は直接インストールできません)。",
    "INSTALL_FAILED_INVALID_APK": "APK が壊れているか、対応していない形式です。",
}


def failure_hint(output: str) -> str | None:
    for code, hint in FAILURE_HINTS.items():
        if code in output:
            return hint
    return None


def looks_like_apk(path: Path) -> bool:
    """拡張子が .apk で、中に AndroidManifest.xml を含む zip かを確認する。"""
    if path.suffix.lower() != ".apk" or not path.is_file():
        return False
    try:
        with zipfile.ZipFile(path) as archive:
            return "AndroidManifest.xml" in archive.namelist()
    except (zipfile.BadZipFile, OSError):
        return False


# ---------------------------------------------------------------------------
# GUI
# ---------------------------------------------------------------------------
def run_gui(initial_apks: list[Path]) -> None:
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    from tkinter.scrolledtext import ScrolledText

    class App(tk.Tk):
        def __init__(self) -> None:
            super().__init__()
            self.title(APP_TITLE)
            self.geometry("760x640")
            self.minsize(640, 560)

            self.adb: str | None = find_adb()
            self.devices: list[Device] = []
            self.apks: list[Path] = []
            self.busy = False
            self._on_idle: Callable[[], None] | None = None
            self.queue: queue.Queue[tuple[str, object]] = queue.Queue()

            self._build_ui()
            self._refresh_adb_label()
            for apk in initial_apks:
                self._add_apk(apk)
            self.after(100, self._poll)
            if self.adb:
                self.refresh_devices()
            else:
                self._log("adb が見つかりません。「ADB を準備」を押すと公式の Platform-Tools を取得します。")

        # ---- UI 構築 -------------------------------------------------------
        def _build_ui(self) -> None:
            pad = {"padx": 10, "pady": 4}

            device_frame = ttk.LabelFrame(self, text="1. 接続中の Android 端末")
            device_frame.pack(fill="x", **pad)
            self.device_var = tk.StringVar()
            self.device_box = ttk.Combobox(device_frame, textvariable=self.device_var, state="readonly")
            self.device_box.pack(side="left", fill="x", expand=True, padx=8, pady=8)
            self.refresh_btn = ttk.Button(device_frame, text="更新", command=self.refresh_devices)
            self.refresh_btn.pack(side="left", padx=4, pady=8)
            self.prepare_btn = ttk.Button(device_frame, text="ADB を準備", command=self.prepare_adb)
            self.prepare_btn.pack(side="left", padx=(4, 8), pady=8)

            self.adb_label = ttk.Label(self, foreground="gray")
            self.adb_label.pack(anchor="w", padx=14)

            apk_frame = ttk.LabelFrame(self, text="2. インストールする APK")
            apk_frame.pack(fill="both", expand=False, **pad)
            list_row = ttk.Frame(apk_frame)
            list_row.pack(fill="both", expand=True, padx=8, pady=8)
            self.apk_list = tk.Listbox(list_row, height=6, selectmode="extended")
            self.apk_list.pack(side="left", fill="both", expand=True)
            scroll = ttk.Scrollbar(list_row, orient="vertical", command=self.apk_list.yview)
            scroll.pack(side="left", fill="y")
            self.apk_list.configure(yscrollcommand=scroll.set)

            btn_col = ttk.Frame(list_row)
            btn_col.pack(side="left", fill="y", padx=(8, 0))
            ttk.Button(btn_col, text="追加...", command=self.pick_apks).pack(fill="x", pady=2)
            ttk.Button(btn_col, text="選択を削除", command=self.remove_selected).pack(fill="x", pady=2)
            ttk.Button(btn_col, text="すべてクリア", command=self.clear_apks).pack(fill="x", pady=2)

            opt_frame = ttk.LabelFrame(self, text="3. オプション")
            opt_frame.pack(fill="x", **pad)
            self.opt_replace = tk.BooleanVar(value=True)
            self.opt_downgrade = tk.BooleanVar(value=False)
            self.opt_grant = tk.BooleanVar(value=False)
            ttk.Checkbutton(opt_frame, text="既存アプリを上書き (-r)", variable=self.opt_replace).pack(side="left", padx=8, pady=6)
            ttk.Checkbutton(opt_frame, text="古いバージョンへの変更を許可 (-d)", variable=self.opt_downgrade).pack(side="left", padx=8)
            ttk.Checkbutton(opt_frame, text="権限を自動付与 (-g)", variable=self.opt_grant).pack(side="left", padx=8)

            action_row = ttk.Frame(self)
            action_row.pack(fill="x", **pad)
            self.install_btn = ttk.Button(action_row, text="インストール開始", command=self.start_install)
            self.install_btn.pack(side="left")
            self.progress = ttk.Progressbar(action_row, mode="indeterminate")
            self.progress.pack(side="left", fill="x", expand=True, padx=10)

            log_frame = ttk.LabelFrame(self, text="ログ")
            log_frame.pack(fill="both", expand=True, **pad)
            self.log_box = ScrolledText(log_frame, height=12, state="disabled", wrap="word")
            self.log_box.pack(fill="both", expand=True, padx=6, pady=6)

        # ---- 表示更新 (メインスレッドのみ) --------------------------------
        def _log(self, text: str) -> None:
            self.log_box.configure(state="normal")
            self.log_box.insert("end", text + "\n")
            self.log_box.see("end")
            self.log_box.configure(state="disabled")

        def _refresh_adb_label(self) -> None:
            self.adb_label.configure(text=f"adb: {self.adb}" if self.adb else "adb: 未検出")

        def _set_busy(self, busy: bool) -> None:
            self.busy = busy
            state = "disabled" if busy else "normal"
            for btn in (self.install_btn, self.refresh_btn, self.prepare_btn):
                btn.configure(state=state)
            if busy:
                self.progress.start(12)
            else:
                self.progress.stop()

        def _update_devices(self, devices: list[Device]) -> None:
            self.devices = devices
            labels = [d.label for d in devices]
            self.device_box.configure(values=labels)
            if devices:
                ready = [i for i, d in enumerate(devices) if d.ready]
                self.device_box.current(ready[0] if ready else 0)
                self._log(f"端末を {len(devices)} 台検出しました。")
            else:
                self.device_var.set("")
                self._log("端末が見つかりません。USB デバッグを有効にし、USB 接続を確認してください。")

        def _add_apk(self, path: Path) -> None:
            if not looks_like_apk(path):
                self._log(f"スキップ: APK として認識できません → {path}")
                return
            if path in self.apks:
                return
            self.apks.append(path)
            self.apk_list.insert("end", str(path))

        # ---- ユーザー操作 --------------------------------------------------
        def pick_apks(self) -> None:
            paths = filedialog.askopenfilenames(
                title="APK を選択", filetypes=[("Android APK", "*.apk"), ("すべてのファイル", "*.*")]
            )
            for p in paths:
                self._add_apk(Path(p))

        def remove_selected(self) -> None:
            for index in reversed(self.apk_list.curselection()):
                self.apk_list.delete(index)
                del self.apks[index]

        def clear_apks(self) -> None:
            self.apk_list.delete(0, "end")
            self.apks.clear()

        def refresh_devices(self) -> None:
            adb = self.adb
            if not adb:
                return

            def work() -> None:
                run_cmd([adb, "start-server"], timeout=30)
                _, out = run_cmd([adb, "devices", "-l"], timeout=30)
                self.queue.put(("devices", parse_devices(out)))

            self._spawn(work)

        def prepare_adb(self) -> None:
            def work() -> None:
                self.queue.put(("adb", download_platform_tools(self._threadsafe_log)))

            self._spawn(work)

        def start_install(self) -> None:
            if not self.adb:
                messagebox.showwarning(APP_TITLE, "adb が見つかりません。「ADB を準備」を押してください。")
                return
            index = self.device_box.current()
            if index < 0 or index >= len(self.devices):
                messagebox.showwarning(APP_TITLE, "端末を選択してください。")
                return
            device = self.devices[index]
            if not device.ready:
                messagebox.showwarning(APP_TITLE, f"この端末にはインストールできません。\n{device.state_message}")
                return
            if not self.apks:
                messagebox.showwarning(APP_TITLE, "APK を追加してください。")
                return

            opts = InstallOptions(
                replace=self.opt_replace.get(),
                downgrade=self.opt_downgrade.get(),
                grant_all=self.opt_grant.get(),
            )
            adb, serial, apks = self.adb, device.serial, list(self.apks)

            def work() -> None:
                self._install_all(adb, serial, apks, opts)

            self._spawn(work)

        # ---- バックグラウンド処理 ------------------------------------------
        def _threadsafe_log(self, text: str) -> None:
            self.queue.put(("log", text))

        def _install_all(self, adb: str, serial: str, apks: list[Path], opts: InstallOptions) -> None:
            total = len(apks)
            success = 0
            for i, apk in enumerate(apks, 1):
                self._threadsafe_log(f"[{i}/{total}] {apk.name} をインストール中...")
                try:
                    code, output = run_cmd(build_install_cmd(adb, serial, apk, opts))
                except subprocess.TimeoutExpired:
                    self._threadsafe_log("  → 失敗: タイムアウトしました")
                    continue
                ok, message = interpret_install_result(code, output)
                if ok:
                    success += 1
                    self._threadsafe_log("  → 成功")
                else:
                    self._threadsafe_log(f"  → 失敗: {message}")
                    hint = failure_hint(output)
                    if hint:
                        self._threadsafe_log(f"     対処: {hint}")
            self._threadsafe_log(f"完了: {success}/{total} 件成功")

        def _spawn(self, work: Callable[[], None]) -> None:
            if self.busy:
                return
            self._set_busy(True)

            def target() -> None:
                try:
                    work()
                except Exception as exc:  # 想定外のエラーもログに出す
                    self.queue.put(("log", f"エラー: {exc}"))
                finally:
                    self.queue.put(("busy", False))

            threading.Thread(target=target, daemon=True).start()

        def _poll(self) -> None:
            try:
                while True:
                    kind, payload = self.queue.get_nowait()
                    if kind == "log":
                        self._log(str(payload))
                    elif kind == "busy":
                        self._set_busy(bool(payload))
                        if not self.busy and self._on_idle is not None:
                            callback, self._on_idle = self._on_idle, None
                            callback()
                    elif kind == "devices":
                        self._update_devices(payload)  # type: ignore[arg-type]
                    elif kind == "adb":
                        self.adb = str(payload)
                        self._refresh_adb_label()
                        self._log(f"adb を準備しました: {self.adb}")
                        # 作業中はスキャンできないので、作業終了後に端末一覧を更新する
                        self._on_idle = self.refresh_devices
            except queue.Empty:
                pass
            self.after(100, self._poll)

    App().mainloop()


def main(argv: list[str]) -> int:
    apks = [Path(a) for a in argv[1:]]
    run_gui(apks)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
