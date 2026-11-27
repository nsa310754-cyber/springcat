using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace BionicRunnerPC
{
    public sealed class MainForm : Form
    {
        /// <summary>画面に保持する出力の上限 (文字数)。超えたら古い方から捨てる</summary>
        const int MaxOutput = 200000;
        const int TrimTo = 150000;

        static readonly Regex Ansi = new Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007", RegexOptions.Compiled);

        static readonly Color Bg = Color.Black;
        static readonly Color Bar = Color.FromArgb(0x11, 0x14, 0x18);
        static readonly Color Fg = Color.FromArgb(0xE5, 0xE7, 0xEB);
        static readonly Color Green = Color.FromArgb(0x4A, 0xDE, 0x80);
        static readonly Color Yellow = Color.FromArgb(0xFA, 0xCC, 0x15);
        static readonly Color Red = Color.FromArgb(0xF8, 0x71, 0x71);

        readonly string[] startArgs;
        readonly bool iconJustCreated;
        readonly string hostArch = Wsl.HostArch();

        Label status;
        FlowLayoutPanel toolbar;
        RichTextBox output;
        TextBox input;
        readonly List<Button> needsLinux = new List<Button>();

        readonly StringBuilder pending = new StringBuilder();
        readonly System.Windows.Forms.Timer flushTimer = new System.Windows.Forms.Timer();

        WslShell shell;
        int shellGeneration;
        volatile bool busy;
        bool ready;

        readonly List<string> pendingFiles = new List<string>();
        readonly List<string> history = new List<string>();
        int historyPos;

        public MainForm(string[] args, bool iconJustCreated)
        {
            startArgs = args;
            this.iconJustCreated = iconJustCreated;
            BuildUi();
            flushTimer.Interval = 50;
            flushTimer.Tick += (s, e) => FlushOutput();
            flushTimer.Start();
        }

        // ------------------------------------------------------------------ 画面

        void BuildUi()
        {
            Text = Installer.AppName + (Wsl.IsAdmin ? " (管理者)" : "");
            Size = new Size(980, 640);
            MinimumSize = new Size(560, 360);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Bg;
            AllowDrop = true;
            DragEnter += OnDragEnter;
            DragDrop += OnDragDrop;
            try
            {
                using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("app.ico"))
                    if (s != null) Icon = new Icon(s);
            }
            catch (Exception) { }

            var mono = MonoFont(10f);

            status = new Label
            {
                Dock = DockStyle.Top,
                AutoSize = false,
                Height = 30,
                Padding = new Padding(12, 0, 12, 0),
                TextAlign = ContentAlignment.MiddleLeft,
                BackColor = Bar,
                ForeColor = Yellow,
                Font = new Font(SystemFonts.MessageBoxFont.FontFamily, 9.5f),
            };

            toolbar = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                WrapContents = true,
                BackColor = Bar,
                Padding = new Padding(4, 0, 4, 4),
            };
            AddButton("実行ファイル", true, (s, e) => PickExecutable());
            AddButton("登録済み", true, (s, e) => ShowRegistered());
            AddButton("中断 ^C", true, (s, e) => { if (shell != null) shell.Interrupt(); });
            AddButton("再起動", true, (s, e) => StartShell());
            AddButton("消去", false, (s, e) => output.Clear());
            AddButton("Linux 端末", true, (s, e) => OpenTerminal(false));
            AddButton("Termux 端末", true, (s, e) => OpenTerminal(true));
            AddButton("ファイル", true, (s, e) => OpenExplorer());
            var more = AddButton("セットアップ ▾", false, null);
            var menu = new ContextMenuStrip();
            if (!Wsl.IsAdmin)
            {
                menu.Items.Add("管理者として開き直す (root・#)", null, (s, e) => RestartElevated());
                menu.Items.Add(new ToolStripSeparator());
            }
            menu.Items.Add("セットアップ / やり直し", null, (s, e) => RunSetup(false));
            menu.Items.Add("デスクトップのアイコンを作り直す", null, (s, e) =>
            {
                Installer.EnsureInstalled();
                bool ok = !ready || Installer.AddTerminalShortcuts();
                Note(ok ? "デスクトップのアイコンを作り直しました" : "デスクトップのアイコンを作成できませんでした");
            });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("アンインストール…", null, (s, e) => UninstallAll());
            more.Click += (s, e) => menu.Show(more, new Point(0, more.Height));

            output = new RichTextBox
            {
                Dock = DockStyle.Fill,
                ReadOnly = true,
                BackColor = Bg,
                ForeColor = Fg,
                BorderStyle = BorderStyle.None,
                Font = mono,
                WordWrap = true,
                DetectUrls = false,
                HideSelection = false,
                ScrollBars = RichTextBoxScrollBars.Vertical,
                AllowDrop = true,
            };
            output.DragEnter += OnDragEnter;
            output.DragDrop += OnDragDrop;

            var line = new TableLayoutPanel
            {
                Dock = DockStyle.Bottom,
                Height = 40,
                ColumnCount = 3,
                RowCount = 1,
                BackColor = Bar,
                Padding = new Padding(8, 6, 4, 6),
            };
            line.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            line.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            line.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            var prompt = new Label
            {
                Text = Wsl.Prompt,
                AutoSize = true,
                ForeColor = Wsl.IsAdmin ? Red : Green, // root は目立つ色に
                Font = mono,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(0, 4, 4, 0),
            };
            input = new TextBox
            {
                Dock = DockStyle.Fill,
                BorderStyle = BorderStyle.FixedSingle,
                BackColor = Color.FromArgb(0x1A, 0x1E, 0x24),
                ForeColor = Fg,
                Font = mono,
            };
            input.KeyDown += OnInputKey;
            var send = FlatButton("送信", (s, e) => SubmitInput());
            send.ForeColor = Green;
            line.Controls.Add(prompt, 0, 0);
            line.Controls.Add(input, 1, 0);
            line.Controls.Add(send, 2, 0);

            Controls.Add(output);
            Controls.Add(line);
            Controls.Add(toolbar);
            Controls.Add(status);
            AcceptButton = null;
        }

        static Font MonoFont(float size)
        {
            foreach (var name in new[] { "Cascadia Mono", "Consolas", "MS Gothic" })
            {
                var f = new Font(name, size);
                if (string.Equals(f.Name, name, StringComparison.OrdinalIgnoreCase)) return f;
                f.Dispose();
            }
            return new Font(FontFamily.GenericMonospace, size);
        }

        Button FlatButton(string label, EventHandler click)
        {
            var b = new Button
            {
                Text = label,
                AutoSize = true,
                FlatStyle = FlatStyle.Flat,
                ForeColor = Fg,
                BackColor = Bar,
                Margin = new Padding(2, 4, 2, 0),
                Padding = new Padding(4, 2, 4, 2),
                Font = new Font(SystemFonts.MessageBoxFont.FontFamily, 9.5f),
                Cursor = Cursors.Hand,
                UseVisualStyleBackColor = false,
            };
            b.FlatAppearance.BorderSize = 0;
            b.FlatAppearance.MouseOverBackColor = Color.FromArgb(0x24, 0x29, 0x31);
            b.FlatAppearance.MouseDownBackColor = Color.FromArgb(0x2F, 0x35, 0x3F);
            if (click != null) b.Click += click;
            return b;
        }

        Button AddButton(string label, bool linuxOnly, EventHandler click)
        {
            var b = FlatButton(label, click);
            toolbar.Controls.Add(b);
            if (linuxOnly) needsLinux.Add(b);
            return b;
        }

        void SetReady(bool value)
        {
            ready = value;
            foreach (var b in needsLinux) b.Enabled = value && !busy;
        }

        void SetStatus(string text, Color color)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => SetStatus(text, color))); return; }
            status.Text = text;
            status.ForeColor = color;
        }

        /// <summary>アプリ自身からのお知らせ (シェルの出力と区別するため » を付ける)</summary>
        void Note(string text)
        {
            AppendOutput("» " + text + "\n");
        }

        /// <summary>どのスレッドからでも呼べる。出力が大量でも UI が詰まらないよう、まとめて反映する</summary>
        void AppendOutput(string text)
        {
            lock (pending)
            {
                pending.Append(text);
                if (pending.Length > MaxOutput) pending.Remove(0, pending.Length - TrimTo);
            }
        }

        void FlushOutput()
        {
            string text;
            lock (pending)
            {
                if (pending.Length == 0) return;
                text = pending.ToString();
                pending.Length = 0;
            }
            text = Ansi.Replace(text, "").Replace("\r\n", "\n").Replace("\r", "\n");
            output.AppendText(text);
            if (output.TextLength > MaxOutput)
            {
                output.Select(0, output.TextLength - TrimTo);
                output.ReadOnly = false;
                output.SelectedText = "";
                output.ReadOnly = true;
                output.Select(output.TextLength, 0);
            }
            output.ScrollToCaret();
        }

        // --------------------------------------------------------------- 起動時

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            DarkTitleBar();
            input.Focus();
            SetReady(false);
            Note(Installer.AppName + " — Windows で Linux (glibc) と Android (bionic) の実行ファイルを動かします");
            if (iconJustCreated) Note("デスクトップに「" + Installer.AppName + "」のアイコンを追加しました");
            SetStatus("確認中…", Yellow);
            bool continuing = startArgs.Contains("--continue-setup");
            ThreadPool.QueueUserWorkItem(_ =>
            {
                bool wsl = Wsl.IsInstalled();
                bool distro = wsl && Wsl.DistroExists();
                int ver = distro ? Wsl.SetupVersion() : 0;
                bool setup = ver >= Setup.CurrentSetupVersion;
                BeginInvoke(new Action(() =>
                {
                    if (setup)
                    {
                        // アイコンにドラッグされたファイルは、シェルの準備ができてから開く
                        pendingFiles.AddRange(startArgs.Where(a => !a.StartsWith("--", StringComparison.Ordinal) && File.Exists(a)));
                        StartShell();
                        return;
                    }
                    SetStatus(ver > 0 ? "Linux 側の更新が必要です — 「セットアップ」を押してください"
                                      : "未セットアップ — 「セットアップ」を押してください", Yellow);
                    if (ver > 0)
                    {
                        // 作成済みの Linux に、新しい版の準備 (ユーザー設定など) を足すだけ
                        if (Ask("Linux 側の更新があります (一般ユーザー $ と root # の切り替えに対応)。\n"
                                + "数十秒かかります。今すぐ更新しますか?"))
                            RunSetup(false);
                        return;
                    }
                    if (continuing || Ask("Linux を使えるようにします。\n\n"
                            + "・Windows の WSL を有効にします (未設定の場合のみ・管理者の確認あり)\n"
                            + "・Ubuntu 24.04 (glibc) と Android の bionic・Termux を入れます\n"
                            + "・インターネットから約 200 MB をダウンロードします (ISO イメージは使いません)\n\n"
                            + "始めますか?"))
                        RunSetup(continuing);
                }));
            });
        }

        void DarkTitleBar()
        {
            try
            {
                int on = 1;
                DwmSetWindowAttribute(Handle, 20, ref on, 4); // DWMWA_USE_IMMERSIVE_DARK_MODE
            }
            catch (Exception) { }
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        bool Ask(string message)
        {
            return MessageBox.Show(this, message, Installer.AppName, MessageBoxButtons.YesNo,
                                   MessageBoxIcon.Question) == DialogResult.Yes;
        }

        // -------------------------------------------------------------- セットアップ

        void RunSetup(bool resumed)
        {
            if (busy) return;
            busy = true;
            SetReady(false);
            StopShell();
            if (resumed) Note("再起動前のセットアップの続きを行います");
            var setup = new Setup();
            setup.Log += s => AppendOutput(s + "\n");
            setup.Step += s => SetStatus("セットアップ中: " + s, Yellow);
            setup.Confirm = msg => (bool)Invoke(new Func<bool>(() => Ask(msg)));
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var result = setup.Run();
                BeginInvoke(new Action(() =>
                {
                    busy = false;
                    switch (result)
                    {
                        case Setup.Outcome.Done:
                            Note("セットアップが完了しました");
                            StartShell();
                            break;
                        case Setup.Outcome.NeedReboot:
                            SetStatus("PC の再起動が必要です", Yellow);
                            Note("WSL を有効にしました。PC を再起動すると、続きが自動で始まります。");
                            if (Ask("WSL を有効にするには PC の再起動が必要です。\n今すぐ再起動しますか?\n\n"
                                    + "(再起動後、自動で続きから始まります)"))
                                Process.Start(new ProcessStartInfo("shutdown.exe", "/r /t 5")
                                { CreateNoWindow = true, UseShellExecute = false });
                            break;
                        case Setup.Outcome.Cancelled:
                            SetStatus("未セットアップ — 「セットアップ」を押してください", Yellow);
                            Note("セットアップを中止しました");
                            break;
                        default:
                            SetStatus("セットアップに失敗しました — 上のメッセージを確認してください", Red);
                            break;
                    }
                }));
            });
        }

        void UninstallAll()
        {
            if (busy) return;
            if (MessageBox.Show(this,
                    "Linux 環境 (" + Wsl.Distro + ") と、その中のファイル・登録した実行ファイルをすべて削除し、"
                    + "アイコンも消します。\n元に戻せません。よろしいですか?\n\n(WSL 本体は Windows に残ります)",
                    Installer.AppName, MessageBoxButtons.OKCancel, MessageBoxIcon.Warning) != DialogResult.OK)
                return;
            busy = true;
            SetReady(false);
            StopShell();
            SetStatus("削除中…", Yellow);
            ThreadPool.QueueUserWorkItem(_ =>
            {
                Installer.Uninstall();
                BeginInvoke(new Action(() =>
                {
                    MessageBox.Show(this, "削除しました。", Installer.AppName);
                    Close();
                }));
            });
        }

        // ---------------------------------------------------------------- シェル

        void StopShell()
        {
            shellGeneration++;
            if (shell != null) shell.Close();
            shell = null;
        }

        void StartShell()
        {
            StopShell();
            int gen = shellGeneration;
            SetStatus("シェル起動中…", Yellow);
            var s = new WslShell();
            s.Output += text => { if (gen == shellGeneration) AppendOutput(text); };
            s.Exited += code => BeginInvoke(new Action(() =>
            {
                if (gen != shellGeneration) return;
                Note("シェルが終了しました (終了コード " + code + ")。「再起動」で起動し直せます");
                SetStatus("シェル停止中", Red);
            }));
            shell = s;
            string init = "cd ~; echo \"» Linux $(uname -r | cut -d- -f1) / $(getconf GNU_LIBC_VERSION 2>/dev/null)"
                        + " / bionic $(test -x /system/bin/linker64 && echo OK || echo なし)\"";
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    s.Start(Wsl.LinuxUser, init);
                }
                catch (Exception e)
                {
                    BeginInvoke(new Action(() =>
                    {
                        if (gen != shellGeneration) return;
                        Note("シェルを起動できませんでした: " + e.Message);
                        SetStatus("シェル停止中", Red);
                    }));
                    return;
                }
                BeginInvoke(new Action(() =>
                {
                    if (gen != shellGeneration) return;
                    SetReady(true);
                    SetStatus("準備完了  ·  " + (Wsl.IsAdmin ? "管理者 → root (#)" : "一般ユーザー user ($)")
                              + "  ·  CPU " + hostArch + "  ·  他の CPU (" + Foreign + ") は qemu で実行", Green);
                    if (Wsl.IsAdmin)
                        Note("管理者として起動したので root (#) で動いています。Linux 端末・Termux 端末も root で開きます。");
                    else
                        Note("一般ユーザー ($) で動いています。root (#) を使うには、アイコンを右クリック →「管理者として実行」"
                             + "(または「セットアップ ▾」→「管理者として開き直す」)。");
                    Note("「実行ファイル」で ELF (glibc / bionic) を選ぶか、ウィンドウにドラッグするか、下の欄にコマンドを入力してください。");
                    Note("termux と入力すると Termux (bionic) の環境でコマンドを実行できます (例: termux pkg install python)。"
                         + (Wsl.IsAdmin ? " pkg は root では動かないため、termux コマンドは一般ユーザーで実行されます。" : ""));
                    var files = pendingFiles.ToArray();
                    pendingFiles.Clear();
                    foreach (var f in files) OpenLocalFile(f, Path.GetFileName(f));
                }));
            });
        }

        /// <summary>同じ exe を管理者として起動し直す (UAC の確認が出る)</summary>
        void RestartElevated()
        {
            if (busy) return;
            try
            {
                Process.Start(new ProcessStartInfo(Installer.CurrentExe, "--elevated-restart")
                { UseShellExecute = true, Verb = "runas" });
            }
            catch (System.ComponentModel.Win32Exception)
            {
                Note("管理者としての起動を取りやめました");
                return;
            }
            Close();
        }

        string Foreign
        {
            get { return hostArch == "aarch64" ? "x86_64" : "ARM64 / ARM"; }
        }

        void OnInputKey(object sender, KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                SubmitInput();
            }
            else if (e.Control && e.KeyCode == Keys.C && input.SelectionLength == 0)
            {
                e.SuppressKeyPress = true;
                if (shell != null) shell.Interrupt();
                Note("^C");
            }
            else if (e.KeyCode == Keys.Up || e.KeyCode == Keys.Down)
            {
                e.SuppressKeyPress = true;
                if (history.Count == 0) return;
                historyPos = Math.Max(0, Math.Min(history.Count, historyPos + (e.KeyCode == Keys.Up ? -1 : 1)));
                input.Text = historyPos < history.Count ? history[historyPos] : "";
                input.SelectionStart = input.TextLength;
            }
        }

        void SubmitInput()
        {
            string line = input.Text;
            input.Text = "";
            if (line.Trim().Length > 0 && (history.Count == 0 || history[history.Count - 1] != line)) history.Add(line);
            historyPos = history.Count;
            SendLine(line);
        }

        void SendLine(string line)
        {
            if (shell == null || !shell.IsAlive)
            {
                Note("シェルが動いていません。" + (ready ? "「再起動」を押してください" : "先に「セットアップ」を行ってください"));
                return;
            }
            AppendOutput(Wsl.Prompt + " " + line + "\n"); // 疑似端末ではないので入力は自分で表示する
            if (!shell.Send(line)) Note("シェルに送信できませんでした。「再起動」を押してください");
        }

        void OpenTerminal(bool termux)
        {
            try
            {
                Wsl.OpenTerminal(termux);
            }
            catch (Exception e)
            {
                Note("端末を開けませんでした: " + e.Message);
            }
        }

        void OpenExplorer()
        {
            try
            {
                Process.Start("explorer.exe", Wsl.UncPath(Wsl.BinDir));
            }
            catch (Exception e)
            {
                Note("エクスプローラーを開けませんでした: " + e.Message);
            }
        }

        // ------------------------------------------------------- ファイルの受け取り

        void OnDragEnter(object sender, DragEventArgs e)
        {
            if (ready && e.Data.GetDataPresent(DataFormats.FileDrop)) e.Effect = DragDropEffects.Copy;
        }

        void OnDragDrop(object sender, DragEventArgs e)
        {
            var files = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (files == null) return;
            foreach (var f in files) if (File.Exists(f)) OpenLocalFile(f, Path.GetFileName(f));
        }

        void PickExecutable()
        {
            using (var d = new OpenFileDialog())
            {
                d.Title = "実行ファイルを選択 (Linux / Android 用の ELF、スクリプト、APK)";
                d.Filter = "すべてのファイル (*.*)|*.*";
                d.Multiselect = false;
                if (d.ShowDialog(this) == DialogResult.OK) OpenLocalFile(d.FileName, Path.GetFileName(d.FileName));
            }
        }

        /// <summary>中身を見て APK (ZIP) か実行ファイルかを振り分ける</summary>
        void OpenLocalFile(string path, string displayName)
        {
            if (!ready)
            {
                Note("先に「セットアップ」を行ってください");
                return;
            }
            ElfInfo info;
            try
            {
                info = ElfInfo.Read(path);
            }
            catch (Exception e)
            {
                Note("読み込みに失敗しました: " + e.Message);
                return;
            }
            if (info.kind == ElfInfo.Kind.Zip)
            {
                PickFromApk(path, displayName);
                return;
            }
            ShowExecDialog(path, displayName, info, null);
        }

        /// <summary>APK の lib/&lt;ABI&gt;/ にあるネイティブの実行ファイル・ライブラリを取り出す</summary>
        void PickFromApk(string path, string displayName)
        {
            List<string> entries;
            try
            {
                using (var z = new ZipArchive(File.OpenRead(path), ZipArchiveMode.Read))
                    entries = z.Entries.Where(en => en.FullName.StartsWith("lib/", StringComparison.Ordinal)
                                                    && en.Length > 0)
                                       .Select(en => en.FullName + "  (" + (en.Length / 1024) + " KB)").ToList();
            }
            catch (Exception e)
            {
                Note("ZIP として読めませんでした: " + e.Message);
                return;
            }
            if (entries.Count == 0)
            {
                Note(displayName + ": APK / ZIP の中にネイティブのファイル (lib/…) がありません。"
                     + "APK は Android 端末用のアプリなので、PC ではそのまま動きません。");
                return;
            }
            int which = ListDialog.Show(this, displayName,
                "APK は Android 端末用のアプリなので PC ではそのまま動きませんが、"
                + "中のネイティブの実行ファイル (lib/<ABI>/…) を取り出して実行できます。", entries);
            if (which < 0) return;
            string entryName = entries[which].Substring(0, entries[which].LastIndexOf("  (", StringComparison.Ordinal));
            string tmp = Path.Combine(Path.GetTempPath(), "bionic-runner-" + Guid.NewGuid().ToString("N"));
            try
            {
                using (var z = new ZipArchive(File.OpenRead(path), ZipArchiveMode.Read))
                using (var o = File.Create(tmp))
                using (var i = z.GetEntry(entryName).Open())
                    i.CopyTo(o);
                var info = ElfInfo.Read(tmp);
                string name = Path.GetFileName(entryName);
                // Android アプリは実行ファイルを libxxx.so という名前で入れていることが多い
                if (name.StartsWith("lib", StringComparison.Ordinal) && name.EndsWith(".so", StringComparison.Ordinal)
                    && info.kind != ElfInfo.Kind.SharedObject)
                    name = name.Substring(3, name.Length - 6);
                ShowExecDialog(tmp, name, info, tmp);
            }
            catch (Exception e)
            {
                Note("取り出しに失敗しました: " + e.Message);
                try { File.Delete(tmp); } catch (Exception) { }
            }
        }

        /// <summary>bin に置くファイル名として安全な文字だけにする</summary>
        static string SafeName(string raw)
        {
            string s = raw;
            int slash = Math.Max(s.LastIndexOf('/'), Math.Max(s.LastIndexOf('\\'), s.LastIndexOf(':')));
            if (slash >= 0) s = s.Substring(slash + 1);
            s = Regex.Replace(s, "[^A-Za-z0-9._+-]", "_");
            s = s.TrimStart('.', '-');
            if (s.Length == 0) s = "program";
            if (s.Length > 64) s = s.Substring(0, 64);
            return s;
        }

        // ------------------------------------------------------------ 実行ファイル

        enum Runnable { Native, Emulated, No }

        string[] NativeAbis
        {
            get { return hostArch == "aarch64" ? new[] { "arm64-v8a" } : new[] { "x86_64", "x86" }; }
        }

        string[] EmulatedAbis
        {
            get { return hostArch == "aarch64" ? new[] { "x86_64", "x86", "armeabi-v7a" } : new[] { "arm64-v8a", "armeabi-v7a" }; }
        }

        Runnable Classify(ElfInfo info)
        {
            if (info.kind == ElfInfo.Kind.Script) return Runnable.Native;
            if (info.kind != ElfInfo.Kind.BionicDynamic && info.kind != ElfInfo.Kind.Static
                && info.kind != ElfInfo.Kind.ForeignLibc) return Runnable.No;
            string abi = info.Abi();
            if (abi == null) return Runnable.No;
            if (NativeAbis.Contains(abi)) return Runnable.Native;
            if (EmulatedAbis.Contains(abi)) return Runnable.Emulated;
            return Runnable.No;
        }

        string Describe(ElfInfo info)
        {
            var sb = new StringBuilder();
            switch (info.kind)
            {
                case ElfInfo.Kind.BionicDynamic: sb.Append("形式: ELF / bionic 動的リンク (Android 用)\n"); break;
                case ElfInfo.Kind.Static: sb.Append("形式: ELF / 静的リンク\n"); break;
                case ElfInfo.Kind.ForeignLibc:
                    sb.Append(info.IsMusl ? "形式: ELF / musl 動的リンク (Alpine など)\n"
                                          : "形式: ELF / glibc 動的リンク (普通の Linux 用)\n");
                    break;
                case ElfInfo.Kind.SharedObject: sb.Append("形式: ELF / 共有ライブラリ (.so) の可能性\n"); break;
                case ElfInfo.Kind.Script: sb.Append("形式: スクリプト (#!" + info.shebang + ")\n"); break;
                case ElfInfo.Kind.WindowsPe: sb.Append("形式: Windows の実行ファイル (PE / .exe)\n"); break;
                default: sb.Append("形式: 不明 (ELF でもスクリプトでもありません)\n"); break;
            }
            if (info.IsElf())
            {
                sb.Append("CPU: ").Append(info.MachineName()).Append(info.is64 ? " (64bit)" : " (32bit)").Append('\n');
                if (info.interpreter != null) sb.Append("ローダ: ").Append(info.interpreter).Append('\n');
            }
            sb.Append('\n');
            var run = Classify(info);
            switch (info.kind)
            {
                case ElfInfo.Kind.BionicDynamic:
                case ElfInfo.Kind.Static:
                case ElfInfo.Kind.ForeignLibc:
                    if (run == Runnable.Native)
                    {
                        if (info.kind == ElfInfo.Kind.BionicDynamic)
                            sb.Append("✔ この PC で実行できます (/system に置いた Android の bionic で起動します)。");
                        else if (info.kind == ElfInfo.Kind.ForeignLibc && !info.is64)
                            sb.Append("△ 32bit 用です。glibc の 32bit ライブラリが必要です (apt install libc6-i386)。");
                        else
                            sb.Append("✔ この PC で実行できます。");
                    }
                    else if (run == Runnable.Emulated)
                    {
                        if (info.kind == ElfInfo.Kind.ForeignLibc && (info.IsMusl || !info.is64))
                            sb.Append("✖ この CPU (" + info.MachineName() + ") 用の " + (info.IsMusl ? "musl" : "32bit glibc")
                                      + " は用意していません。bionic 版か静的リンク版を使ってください。");
                        else
                            sb.Append("✔ CPU が違うため qemu で模擬して実行します (速度は落ちます)。");
                    }
                    else
                    {
                        sb.Append("✖ CPU が合いません。この PC で動くのは "
                                  + string.Join(", ", NativeAbis.Concat(EmulatedAbis)) + " 用です。");
                    }
                    break;
                case ElfInfo.Kind.SharedObject:
                    sb.Append("✖ 共有ライブラリのようです。通常は単体では実行できません (古いリンカで作った静的 PIE の場合は実行できます)。");
                    break;
                case ElfInfo.Kind.Script:
                    sb.Append("✔ スクリプトとして実行できます (改行が CRLF だと動かないことがあります)。");
                    break;
                case ElfInfo.Kind.WindowsPe:
                    sb.Append("✖ Windows 用の .exe です。Linux ではなく、そのままダブルクリックで実行してください。");
                    break;
                default:
                    sb.Append("✖ 実行できる形式ではなさそうです。");
                    break;
            }
            return sb.ToString();
        }

        void ShowExecDialog(string localPath, string name, ElfInfo info, string deleteAfter)
        {
            var d = new ExecDialog(name, Describe(info), SafeName(name), true);
            var r = d.ShowDialog(this);
            string regName = d.RegName;
            string args = d.Args;
            d.Dispose();
            if (r == DialogResult.Cancel)
            {
                Cleanup(deleteAfter);
                return;
            }
            bool runAfter = r == DialogResult.OK;
            string safe = SafeName(regName);
            bool wrap = Classify(info) == Runnable.Emulated;
            Note("登録中: " + safe);
            ThreadPool.QueueUserWorkItem(_ =>
            {
                Wsl.Result res;
                try
                {
                    using (var f = File.OpenRead(localPath))
                        res = Wsl.Sh(
                            "set -e; d=" + Wsl.BinDir + "; n=\"$1\"; mkdir -p \"$d/.real\"; rm -f \"$d/$n\" \"$d/.real/$n\";"
                            + " if [ \"$2\" = wrap ]; then cat > \"$d/.real/$n\"; chmod 755 \"$d/.real/$n\";"
                            + " printf '#!/bin/sh\\nexec brun %s \"$@\"\\n' \"$d/.real/$n\" > \"$d/$n\";"
                            + " else cat > \"$d/$n\"; fi; chmod 755 \"$d/$n\"",
                            new[] { safe, wrap ? "wrap" : "plain" }, f, null, 600000);
                }
                catch (Exception e)
                {
                    res = new Wsl.Result { Code = -1, Output = e.Message };
                }
                Cleanup(deleteAfter);
                BeginInvoke(new Action(() =>
                {
                    if (!res.Ok)
                    {
                        Note("登録に失敗しました: " + res.Output);
                        return;
                    }
                    Note("登録しました: " + safe + " (コマンド名としてそのまま入力できます"
                         + (wrap ? "。qemu 経由で動きます" : "") + ")");
                    if (runAfter) Run(safe, args);
                }));
            });
        }

        static void Cleanup(string path)
        {
            if (path == null) return;
            try { File.Delete(path); } catch (Exception) { }
        }

        void Run(string name, string args)
        {
            string a = args.Trim();
            SendLine(a.Length == 0 ? name : name + " " + a);
        }

        void ShowRegistered()
        {
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var r = Wsl.Sh("cd " + Wsl.BinDir + " 2>/dev/null || exit 0; for f in *; do [ -f \"$f\" ] || continue;"
                               + " r=\"$f\"; [ -f \".real/$f\" ] && r=\".real/$f\"; echo \"$(stat -c %s \"$r\") $f\"; done");
                var names = new List<string>();
                var labels = new List<string>();
                foreach (var line in r.Output.Split('\n'))
                {
                    var p = line.Trim().Split(new[] { ' ' }, 2);
                    long size;
                    if (p.Length != 2 || !long.TryParse(p[0], out size)) continue;
                    names.Add(p[1]);
                    labels.Add(p[1] + "  (" + (size / 1024) + " KB)");
                }
                BeginInvoke(new Action(() =>
                {
                    if (names.Count == 0)
                    {
                        Note("登録済みの実行ファイルはありません");
                        return;
                    }
                    int which = ListDialog.Show(this, "登録済みの実行ファイル", Wsl.BinDir, labels);
                    if (which >= 0) ShowRegisteredItem(names[which]);
                }));
            });
        }

        void ShowRegisteredItem(string name)
        {
            ElfInfo info;
            try
            {
                // qemu 用のラッパーで登録したものは、本体が .real/ にある
                var data = Wsl.ReadFile(Wsl.BinDir + "/.real/" + name);
                if (data.Length == 0) data = Wsl.ReadFile(Wsl.BinDir + "/" + name);
                info = ElfInfo.Read(new MemoryStream(data));
            }
            catch (Exception)
            {
                info = new ElfInfo();
            }
            var d = new ExecDialog(name, Describe(info), null, false);
            var r = d.ShowDialog(this);
            string args = d.Args;
            d.Dispose();
            if (r == DialogResult.OK)
            {
                Run(name, args);
            }
            else if (r == DialogResult.Abort)
            {
                ThreadPool.QueueUserWorkItem(_ =>
                {
                    var res = Wsl.Sh("rm -f \"" + Wsl.BinDir + "/$1\" \"" + Wsl.BinDir + "/.real/$1\"", new[] { name });
                    AppendOutput("» " + (res.Ok ? "削除しました: " + name : "削除に失敗しました: " + res.Output) + "\n");
                });
            }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (busy && MessageBox.Show(this, "セットアップ中です。終了しますか?", Installer.AppName,
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            {
                e.Cancel = true;
                return;
            }
            StopShell();
            base.OnFormClosing(e);
        }
    }
}
