using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Management;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.6.2", FrameworkDisplayName = ".NET Framework 4.6.2")]
[assembly: System.Reflection.AssemblyTitle("実行コード保存")]
[assembly: System.Reflection.AssemblyProduct("ExecSaver")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace ExecSaver
{
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--selftest")
                return SelfTest.Run(System.Reflection.Assembly.GetExecutingAssembly().Location);
            bool created;
            using (var m = new Mutex(true, "ExecSaver_single_instance", out created))
            {
                if (!created) { MessageBox.Show("実行コード保存はすでに起動しています（通知領域を見てください）。", "実行コード保存"); return 0; }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm(args.Contains("--tray")));
            }
            return 0;
        }
    }

    /// <summary>ほかのプロセスの情報を Win32 API で読む。</summary>
    static class ProcInfo
    {
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(int access, bool inherit, int pid);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll")] static extern bool ReadProcessMemory(IntPtr h, IntPtr addr, byte[] buf, IntPtr size, out IntPtr read);
        [DllImport("kernel32.dll")] static extern bool IsWow64Process(IntPtr h, out bool wow);
        [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr h, int cls, IntPtr[] info, int len, out int retLen);
        [DllImport("shell32.dll")] static extern int SHGetKnownFolderPath(ref Guid id, int flags, IntPtr token, out IntPtr path);

        public static string ImagePath(int pid)
        {
            IntPtr h = OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */, false, pid);
            if (h == IntPtr.Zero) return null;
            try
            {
                var sb = new StringBuilder(1024); int n = sb.Capacity;
                return QueryFullProcessImageName(h, 0, sb, ref n) ? sb.ToString() : null;
            }
            finally { CloseHandle(h); }
        }

        static IntPtr ReadPtr(IntPtr h, IntPtr addr)
        {
            var b = new byte[IntPtr.Size]; IntPtr r;
            if (!ReadProcessMemory(h, addr, b, (IntPtr)b.Length, out r)) throw new Exception();
            return IntPtr.Size == 8 ? (IntPtr)BitConverter.ToInt64(b, 0) : (IntPtr)BitConverter.ToInt32(b, 0);
        }

        /// <summary>そのプロセスの作業フォルダー (相対パスで指定されたスクリプトを探すため)。取れなければ null。</summary>
        public static string CurrentDirectory(int pid)
        {
            IntPtr h = OpenProcess(0x0400 | 0x0010 /* QUERY_INFORMATION | VM_READ */, false, pid);
            if (h == IntPtr.Zero) return null;
            try
            {
                bool wow; IsWow64Process(h, out wow);
                bool selfWow; IsWow64Process(Process.GetCurrentProcess().Handle, out selfWow);
                if (wow != selfWow) return null;     // 32bit と 64bit が違うと構造が違うので読まない
                var pbi = new IntPtr[6]; int len;
                if (NtQueryInformationProcess(h, 0, pbi, IntPtr.Size * 6, out len) != 0) return null;
                IntPtr peb = pbi[1];
                bool x64 = IntPtr.Size == 8;
                IntPtr parameters = ReadPtr(h, peb + (x64 ? 0x20 : 0x10));
                var us = new byte[x64 ? 16 : 8]; IntPtr r;
                if (!ReadProcessMemory(h, parameters + (x64 ? 0x38 : 0x24), us, (IntPtr)us.Length, out r)) return null;
                int bytes = BitConverter.ToUInt16(us, 0);
                IntPtr buf = x64 ? (IntPtr)BitConverter.ToInt64(us, 8) : (IntPtr)BitConverter.ToInt32(us, 4);
                if (bytes == 0 || bytes > 4096) return null;
                var s = new byte[bytes];
                if (!ReadProcessMemory(h, buf, s, (IntPtr)bytes, out r)) return null;
                return Encoding.Unicode.GetString(s);
            }
            catch { return null; }
            finally { CloseHandle(h); }
        }

        public static string Downloads()
        {
            try
            {
                var id = new Guid("374DE290-123F-4565-9164-39C4925E467B");
                IntPtr p;
                if (SHGetKnownFolderPath(ref id, 0, IntPtr.Zero, out p) == 0)
                {
                    string s = Marshal.PtrToStringUni(p); Marshal.FreeCoTaskMem(p);
                    if (!string.IsNullOrEmpty(s)) return s;
                }
            }
            catch { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        }
    }

    /// <summary>新しく起動したプロセスを WMI で受け取る。</summary>
    sealed class ProcessWatcher : IDisposable
    {
        public event Action<int, string, string> Started;   // pid, 実行ファイル, コマンドライン
        IDisposable w;      // ManagementEventWatcher (WMI が無い環境でもクラス自体は読み込めるよう object で持つ)

        public void Start()
        {
            Dispose();
            w = Create();
        }

        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        IDisposable Create()
        {
            var q = new WqlEventQuery("__InstanceCreationEvent", TimeSpan.FromMilliseconds(500), "TargetInstance ISA 'Win32_Process'");
            var mw = new ManagementEventWatcher(q);
            mw.EventArrived += (s, e) =>
            {
                try
                {
                    var p = (ManagementBaseObject)e.NewEvent["TargetInstance"];
                    int pid = Convert.ToInt32(p["ProcessId"]);
                    var h = Started;
                    if (h != null) h(pid, p["ExecutablePath"] as string, p["CommandLine"] as string);
                }
                catch { }
            };
            mw.Start();
            return mw;
        }

        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        static void StopWatcher(IDisposable x) { ((ManagementEventWatcher)x).Stop(); }

        public void Dispose()
        {
            if (w == null) return;
            try { StopWatcher(w); } catch { }
            try { w.Dispose(); } catch { }
            w = null;
        }
    }

    sealed class Settings
    {
        static readonly string FilePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ExecSaver", "settings.txt");
        public bool Exclude = true, Notify = true, ShowAll = false;

        public static Settings Load()
        {
            var s = new Settings();
            try
            {
                foreach (var line in File.ReadAllLines(FilePath))
                {
                    var kv = line.Split(new[] { '=' }, 2);
                    if (kv.Length != 2) continue;
                    bool v = kv[1].Trim() == "1";
                    if (kv[0] == "exclude") s.Exclude = v;
                    else if (kv[0] == "notify") s.Notify = v;
                    else if (kv[0] == "showall") s.ShowAll = v;
                }
            }
            catch { }
            return s;
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
                File.WriteAllLines(FilePath, new[] { "exclude=" + (Exclude ? 1 : 0), "notify=" + (Notify ? 1 : 0), "showall=" + (ShowAll ? 1 : 0) });
            }
            catch { }
        }
    }

    class MainForm : Form
    {
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run", RunName = "ExecSaver";
        readonly Settings settings = Settings.Load();
        readonly Saver saver;
        readonly ProcessWatcher watcher = new ProcessWatcher();
        readonly ListView list = new ListView { View = View.Details, FullRowSelect = true, Dock = DockStyle.Fill, HideSelection = false };
        readonly Button toggle = new Button { Width = 150, Height = 34 };
        readonly Label status = new Label { AutoSize = true, Margin = new Padding(8, 10, 0, 0) };
        readonly CheckBox excludeBox = new CheckBox { Text = "Windows・Program Files 内のプログラムは保存しない", AutoSize = true };
        readonly CheckBox notifyBox = new CheckBox { Text = "保存したら通知する", AutoSize = true };
        readonly CheckBox showAllBox = new CheckBox { Text = "保存しなかったもの（対象外・保存済み）も一覧に出す", AutoSize = true };
        readonly CheckBox autoBox = new CheckBox { Text = "Windows の起動時に自動で監視を始める", AutoSize = true };
        readonly NotifyIcon tray = new NotifyIcon();
        readonly int selfPid = Process.GetCurrentProcess().Id;
        bool watching, reallyExit, startHidden, toldTray;
        int savedCount;

        public MainForm(bool hidden)
        {
            startHidden = hidden;
            saver = new Saver(Path.Combine(ProcInfo.Downloads(), "exe実行コード"));
            foreach (var d in new[] { Environment.GetFolderPath(Environment.SpecialFolder.Windows),
                                      Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                                      Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86) })
                if (!string.IsNullOrEmpty(d) && !saver.ExcludeDirs.Contains(d, StringComparer.OrdinalIgnoreCase)) saver.ExcludeDirs.Add(d);
            saver.Exclude = settings.Exclude;

            Text = "実行コード保存"; Font = new Font("Yu Gothic UI", 9.5f);
            AutoScaleDimensions = new SizeF(96f, 96f); AutoScaleMode = AutoScaleMode.Dpi;
            Size = new Size(920, 560); MinimumSize = new Size(640, 400); StartPosition = FormStartPosition.CenterScreen;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var top = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(8, 8, 8, 0) };
            toggle.Click += (s, e) => { if (watching) StopWatch(); else StartWatch(); };
            var open = new Button { Text = "保存先を開く", Width = 120, Height = 34 };
            open.Click += (s, e) => { Directory.CreateDirectory(saver.Root); Process.Start("explorer.exe", "\"" + saver.Root + "\""); };
            var clear = new Button { Text = "一覧を消す", Width = 100, Height = 34 };
            clear.Click += (s, e) => list.Items.Clear();
            top.Controls.AddRange(new Control[] { toggle, open, clear, status });

            var opts = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(8, 4, 8, 4) };
            opts.Controls.AddRange(new Control[] { excludeBox, notifyBox, showAllBox, autoBox });
            var where = new Label { Dock = DockStyle.Bottom, AutoSize = false, Height = 40, Padding = new Padding(8, 4, 8, 4), ForeColor = Color.DimGray,
                Text = "保存先: " + saver.Root + "\\(実行したファイル名)\\(実行したファイル名).(形式に合った拡張子)　　ダブルクリックで保存したファイルの場所を開きます" +
                       (IsAdmin() ? "" : "\n※ 管理者として実行すると、管理者権限で動くプログラムのコマンドラインも読み取れます") };

            list.Columns.Add("時刻", 70); list.Columns.Add("実行されたもの", 230); list.Columns.Add("形式", 160); list.Columns.Add("結果", 400);
            list.DoubleClick += (s, e) =>
            {
                if (list.SelectedItems.Count == 0) return;
                string p = list.SelectedItems[0].Tag as string;
                if (p != null && File.Exists(p)) Process.Start("explorer.exe", "/select,\"" + p + "\"");
            };
            Controls.Add(list); Controls.Add(where); Controls.Add(opts); Controls.Add(top);

            excludeBox.Checked = settings.Exclude; notifyBox.Checked = settings.Notify; showAllBox.Checked = settings.ShowAll;
            autoBox.Checked = AutoStartEnabled();
            excludeBox.CheckedChanged += (s, e) => { settings.Exclude = saver.Exclude = excludeBox.Checked; settings.Save(); };
            notifyBox.CheckedChanged += (s, e) => { settings.Notify = notifyBox.Checked; settings.Save(); };
            showAllBox.CheckedChanged += (s, e) => { settings.ShowAll = showAllBox.Checked; settings.Save(); };
            autoBox.CheckedChanged += (s, e) => SetAutoStart(autoBox.Checked);

            tray.Icon = Icon ?? SystemIcons.Application; tray.Text = "実行コード保存"; tray.Visible = true;
            tray.DoubleClick += (s, e) => ShowMe();
            tray.BalloonTipClicked += (s, e) => { Directory.CreateDirectory(saver.Root); Process.Start("explorer.exe", "\"" + saver.Root + "\""); };
            var menu = new ContextMenuStrip();
            menu.Items.Add("画面を表示", null, (s, e) => ShowMe());
            menu.Items.Add("保存先を開く", null, (s, e) => { Directory.CreateDirectory(saver.Root); Process.Start("explorer.exe", "\"" + saver.Root + "\""); });
            menu.Items.Add("終了", null, (s, e) => { reallyExit = true; Close(); });
            tray.ContextMenuStrip = menu;

            watcher.Started += OnStarted;
            Load += (s, e) => StartWatch();
            Shown += (s, e) => { if (startHidden) BeginInvoke((Action)Hide); };
            FormClosing += (s, e) =>
            {
                // × は通知領域にしまうだけ (監視は続ける)。終了は通知領域のメニューから
                if (!reallyExit && e.CloseReason == CloseReason.UserClosing && watching)
                {
                    e.Cancel = true; Hide();
                    if (!toldTray) { toldTray = true; tray.ShowBalloonTip(3000, "実行コード保存", "通知領域で監視を続けています。終了するには右クリック →「終了」。", ToolTipIcon.Info); }
                    return;
                }
                watcher.Dispose(); tray.Visible = false;
            };
            UpdateStatus();
        }

        static bool IsAdmin()
        {
            try { return new System.Security.Principal.WindowsPrincipal(System.Security.Principal.WindowsIdentity.GetCurrent()).IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator); }
            catch { return false; }
        }

        bool AutoStartEnabled()
        {
            try { using (var k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue(RunName) != null; }
            catch { return false; }
        }

        void SetAutoStart(bool on)
        {
            try
            {
                using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
                    if (on) k.SetValue(RunName, "\"" + Application.ExecutablePath + "\" --tray");
                    else k.DeleteValue(RunName, false);
            }
            catch (Exception ex) { MessageBox.Show(this, "自動起動の設定に失敗しました: " + ex.Message, "実行コード保存"); }
        }

        void StartWatch()
        {
            try { watcher.Start(); watching = true; }
            catch (Exception ex)
            {
                watching = false;
                MessageBox.Show(this, "監視を始められませんでした（WMI が使えません）。\n" + ex.Message, "実行コード保存", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            UpdateStatus();
        }

        void StopWatch() { watcher.Dispose(); watching = false; UpdateStatus(); }

        void UpdateStatus()
        {
            toggle.Text = watching ? "■ 監視を止める" : "▶ 監視を始める";
            status.Text = (watching ? "● 監視中" : "停止中") + "　　保存した数: " + savedCount;
            status.ForeColor = watching ? Color.ForestGreen : Color.Gray;
            tray.Text = "実行コード保存 (" + (watching ? "監視中" : "停止中") + ")";
        }

        // WMI のスレッドで呼ばれる。プロセスがすぐ終わることがあるので、作業フォルダーはここで先に読む
        void OnStarted(int pid, string exe, string cmd)
        {
            if (pid == selfPid) return;
            if (string.IsNullOrEmpty(exe)) exe = ProcInfo.ImagePath(pid);
            if (string.IsNullOrEmpty(exe)) return;
            string cwd = ProcInfo.CurrentDirectory(pid);
            if (string.IsNullOrEmpty(cmd)) cmd = "\"" + exe + "\"";
            ThreadPool.QueueUserWorkItem(_ =>
            {
                Target t; SaveResult r;
                try { t = Resolver.Resolve(exe, cmd, cwd); r = saver.Save(t, cmd); }
                catch (Exception ex) { t = new Target { Name = Path.GetFileName(exe) }; r = new SaveResult { Message = "エラー: " + ex.Message }; }
                try { BeginInvoke((Action)(() => Show(t, r, exe))); } catch { }
            });
        }

        void Show(Target t, SaveResult r, string exe)
        {
            bool problem = r.Message != null && (r.Message.StartsWith("エラー") || r.Message.StartsWith("ファイルが見つかりません"));
            if (!r.Saved && !problem && !settings.ShowAll) return;
            if (r.Saved) savedCount++;
            string what = t.IsInline ? Path.GetFileName(exe) + " に直接書かれたコード" : (t.Path != null ? Path.GetFileName(t.Path) : Path.GetFileName(exe));
            if (!t.IsInline && t.Path != null && !string.Equals(t.Path, exe, StringComparison.OrdinalIgnoreCase)) what += "  (" + Path.GetFileName(exe) + " で実行)";
            var it = new ListViewItem(new[] { DateTime.Now.ToString("HH:mm:ss"), what, r.Label != null ? r.Label + " " + r.Ext : "", r.Saved ? r.SavedPath : r.Message + (r.SavedPath != null ? "  " + r.SavedPath : "") });
            it.Tag = r.SavedPath;
            if (r.Saved) it.ForeColor = Color.DarkGreen; else if (problem) it.ForeColor = Color.Firebrick; else it.ForeColor = Color.Gray;
            list.Items.Insert(0, it);
            while (list.Items.Count > 1000) list.Items.RemoveAt(list.Items.Count - 1);
            UpdateStatus();
            if (r.Saved && settings.Notify)
                tray.ShowBalloonTip(2500, "保存しました: " + Path.GetFileName(r.SavedPath), what + "\n" + r.Label, ToolTipIcon.Info);
        }

        void ShowMe() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    }
}
