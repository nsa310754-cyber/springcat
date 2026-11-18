using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.6.2", FrameworkDisplayName = ".NET Framework 4.6.2")]
[assembly: System.Reflection.AssemblyTitle("C2Guard")]
[assembly: System.Reflection.AssemblyProduct("C2Guard")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace C2Guard
{
    static class Program
    {
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--selftest") return SelfTest.Run();
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            bool first;
            using (var m = new System.Threading.Mutex(true, "C2Guard.SingleInstance", out first))
            {
                if (!first) { MessageBox.Show("C2Guard はすでに起動しています。", "C2Guard"); return 0; }
                string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "C2Guard");
                Directory.CreateDirectory(dir);
                Application.Run(new MainForm(dir));
            }
            return 0;
        }
    }

    /// <summary>Windows の機能 (通信一覧・プロセス・ファイアウォール・管理者権限)</summary>
    static class Sys
    {
        [DllImport("iphlpapi.dll", SetLastError = true)]
        static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool order, int af, int tableClass, uint reserved);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);

        const int AF_INET = 2, TCP_TABLE_OWNER_PID_ALL = 5;
        static readonly string[] States = { "", "CLOSED", "LISTEN", "SYN_SENT", "SYN_RCVD", "ESTABLISHED", "FIN_WAIT1",
            "FIN_WAIT2", "CLOSE_WAIT", "CLOSING", "LAST_ACK", "TIME_WAIT", "DELETE_TCB" };

        static string Ip(uint a) { return (a & 255) + "." + ((a >> 8) & 255) + "." + ((a >> 16) & 255) + "." + (a >> 24); }
        static int Port(uint p) { return (int)(((p & 255) << 8) | ((p >> 8) & 255)); }

        /// <summary>今ある IPv4 の TCP 通信 (待ち受けを除く) を、どのプロセスのものかと一緒に返す。</summary>
        public static List<Conn> TcpConnections()
        {
            var list = new List<Conn>();
            int size = 0;
            GetExtendedTcpTable(IntPtr.Zero, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
            for (int attempt = 0; attempt < 3; attempt++)
            {
                IntPtr buf = Marshal.AllocHGlobal(size);
                try
                {
                    uint r = GetExtendedTcpTable(buf, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
                    if (r == 122) continue;                 // 途中で増えた → もう一度
                    if (r != 0) throw new Win32Exception((int)r);
                    int n = Marshal.ReadInt32(buf);
                    var names = new Dictionary<int, string>();
                    for (int i = 0; i < n; i++)
                    {
                        IntPtr row = buf + 4 + i * 24;
                        uint state = (uint)Marshal.ReadInt32(row), la = (uint)Marshal.ReadInt32(row, 4), lp = (uint)Marshal.ReadInt32(row, 8),
                             ra = (uint)Marshal.ReadInt32(row, 12), rp = (uint)Marshal.ReadInt32(row, 16);
                        int pid = Marshal.ReadInt32(row, 20);
                        if (state == 2 || ra == 0) continue;     // LISTEN / 相手なし
                        var c = new Conn
                        {
                            LocalIp = Ip(la), LocalPort = Port(lp), RemoteIp = Ip(ra), RemotePort = Port(rp), Pid = pid,
                            State = state < States.Length ? States[state] : state.ToString(),
                        };
                        string path;
                        if (!names.TryGetValue(pid, out path)) { path = ProcessPath(pid); names[pid] = path; }
                        c.ProcessPath = path;
                        c.ProcessName = path.Length > 0 ? Path.GetFileName(path) : (pid == 4 ? "System" : "PID " + pid);
                        list.Add(c);
                    }
                    return list;
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
            return list;
        }

        public static string ProcessPath(int pid)
        {
            if (pid <= 4) return "";
            IntPtr h = OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */, false, pid);
            if (h == IntPtr.Zero) return "";
            try
            {
                var sb = new StringBuilder(1024); int len = sb.Capacity;
                return QueryFullProcessImageName(h, 0, sb, ref len) ? sb.ToString() : "";
            }
            finally { CloseHandle(h); }
        }

        public static bool IsAdmin()
        {
            try { return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator); }
            catch { return false; }
        }

        /// <summary>netsh を実行する。戻り値は (成功したか, 出力)。</summary>
        public static Tuple<bool, string> Netsh(string args)
        {
            var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "netsh.exe"), args)
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            };
            using (var p = Process.Start(psi))
            {
                string o = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
                p.WaitForExit(15000);
                return Tuple.Create(p.ExitCode == 0, o.Trim());
            }
        }
    }

    /// <summary>見つけたときに出す「ブロックする / 放置する」の確認画面</summary>
    class HitDialog : Form
    {
        public bool Block, KillProcess;

        public HitDialog(Hit h)
        {
            AutoScaleDimensions = new SizeF(96f, 96f); AutoScaleMode = AutoScaleMode.Dpi;
            Text = "C2Guard — 危険な通信を見つけました";
            Font = new Font("Yu Gothic UI", 10f);
            FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen; TopMost = true; ShowInTaskbar = true;
            ClientSize = new Size(560, 340);
            BackColor = Color.White;

            var head = new Label
            {
                Text = "⚠  C2 サーバー（遠隔操作の司令塔）として知られている相手と通信しています",
                Font = new Font("Yu Gothic UI", 11f, FontStyle.Bold), ForeColor = Color.FromArgb(180, 30, 30),
                Location = new Point(16, 14), Size = new Size(528, 48),
            };
            var info = new TextBox
            {
                Multiline = true, ReadOnly = true, BorderStyle = BorderStyle.FixedSingle, BackColor = Color.FromArgb(248, 248, 250),
                Location = new Point(16, 66), Size = new Size(528, 150), ScrollBars = ScrollBars.Vertical,
                Text = "プロセス:  " + h.Conn.ProcessName + "  (PID " + h.Conn.Pid + ")\r\n" +
                       "場所:      " + (h.Conn.ProcessPath.Length > 0 ? h.Conn.ProcessPath : "(取得できません)") + "\r\n" +
                       "通信先:    " + h.Conn.RemoteIp + " : " + h.Conn.RemotePort + "  (" + h.Conn.State + ")\r\n" +
                       "種類:      " + h.Ind.Label + "\r\n" +
                       "リスト:    " + h.Ind.Source,
            };
            var kill = new CheckBox
            {
                Text = "このプロセスも終了する", Location = new Point(16, 226), AutoSize = true,
                Checked = !string.IsNullOrEmpty(h.Conn.ProcessPath) && !IsWindowsPath(h.Conn.ProcessPath),
            };
            var note = new Label
            {
                Text = "ブロック: Windows ファイアウォールでこの相手との通信を止めます（管理者権限が必要）。\r\n" +
                       "放置: 今後この相手は知らせません。あとで「放置リスト」から戻せます。",
                ForeColor = Color.DimGray, Location = new Point(16, 254), Size = new Size(528, 40),
            };
            var bBlock = new Button { Text = "ブロックする", Size = new Size(140, 34), Location = new Point(250, 296), BackColor = Color.FromArgb(200, 40, 40), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
            var bIgnore = new Button { Text = "放置する", Size = new Size(140, 34), Location = new Point(404, 296), FlatStyle = FlatStyle.System };
            bBlock.Click += (s, e) => { Block = true; KillProcess = kill.Checked; DialogResult = DialogResult.OK; };
            bIgnore.Click += (s, e) => { Block = false; DialogResult = DialogResult.OK; };
            Controls.AddRange(new Control[] { head, info, kill, note, bBlock, bIgnore });
            AcceptButton = bBlock;
            CancelButton = null;           // ×で閉じたときは「今回は保留」(次の検出でまた聞く)
            ActiveControl = bBlock;
        }

        static bool IsWindowsPath(string p)
        {
            string win = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            return !string.IsNullOrEmpty(win) && p.StartsWith(win, StringComparison.OrdinalIgnoreCase);
        }
    }

    class MainForm : Form
    {
        readonly Feeds feeds;
        readonly State state;
        readonly Timer timer = new Timer { Interval = 5000 };
        readonly Label status = new Label();
        readonly CheckBox auto = new CheckBox();
        readonly ListView hitsView = MakeList("時刻", 130, "プロセス", 150, "PID", 60, "通信先", 160, "種類", 230, "対応", 90);
        readonly ListView connView = MakeList("プロセス", 170, "PID", 60, "通信先", 170, "状態", 110, "判定", 300);
        readonly ListView blockedView = MakeList("IP", 140, "種類", 260, "プロセス", 160, "ブロックした日時", 160);
        readonly ListView ignoredView = MakeList("IP", 140, "種類", 260, "プロセス", 160, "放置にした日時", 160);
        readonly TextBox logBox = new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Both, WordWrap = false, Dock = DockStyle.Fill, Font = new Font("Consolas", 9.5f) };
        readonly NotifyIcon tray = new NotifyIcon();
        readonly HashSet<string> asking = new HashSet<string>();     // 確認画面を出している IP
        readonly HashSet<string> sessionSkip = new HashSet<string>(); // ×で閉じた IP (この起動中は聞かない)
        readonly HashSet<string> notedBlocked = new HashSet<string>(); // ブロック済みなのに通信が残っていたと記録した IP
        bool scanning, updating;

        static ListView MakeList(params object[] cols)
        {
            var lv = new ListView { View = View.Details, FullRowSelect = true, Dock = DockStyle.Fill, HideSelection = false, GridLines = true };
            for (int i = 0; i < cols.Length; i += 2) lv.Columns.Add((string)cols[i], (int)cols[i + 1]);
            return lv;
        }

        public MainForm(string dir)
        {
            feeds = new Feeds(dir);
            state = State.Load(dir);
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96f, 96f); AutoScaleMode = AutoScaleMode.Dpi;
            Text = "C2Guard — C2 マルウェア通信の検出" + (Sys.IsAdmin() ? "" : "（管理者でない: ブロック不可）");
            Font = new Font("Yu Gothic UI", 9.5f);
            ClientSize = new Size(900, 560); MinimumSize = new Size(700, 420);
            StartPosition = FormStartPosition.CenterScreen;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var top = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 44, Padding = new Padding(6, 6, 6, 4), WrapContents = false };
            AddButton(top, "今すぐスキャン", async (s, e) => await ScanAsync(true));
            AddButton(top, "リストを更新", async (s, e) => await UpdateFeedsAsync(true));
            AddButton(top, "自分用リストを開く", (s, e) => OpenCustom());
            if (!Sys.IsAdmin()) AddButton(top, "管理者で起動し直す", (s, e) => RestartAsAdmin());
            auto.Text = "5 秒ごとに自動で見張る"; auto.AutoSize = true; auto.Checked = state.AutoWatch; auto.Margin = new Padding(10, 5, 0, 0);
            auto.CheckedChanged += (s, e) => { state.AutoWatch = auto.Checked; timer.Enabled = auto.Checked; SaveState(); };
            top.Controls.Add(auto);

            status.Dock = DockStyle.Bottom; status.Height = 26; status.Padding = new Padding(8, 5, 8, 0);
            status.BackColor = Color.FromArgb(240, 242, 246);

            var tabs = new TabControl { Dock = DockStyle.Fill };
            AddTab(tabs, "検出", hitsView);
            AddTab(tabs, "今の通信", connView);
            AddTab(tabs, "ブロック中", WithButton(blockedView, "選んだ IP のブロックを解除", (s, e) => Unblock()));
            AddTab(tabs, "放置リスト", WithButton(ignoredView, "選んだ IP を放置リストから外す（また知らせる）", (s, e) => Unignore()));
            AddTab(tabs, "ログ", logBox);

            Controls.Add(tabs); Controls.Add(top); Controls.Add(status);
            ResumeLayout(false); PerformLayout();

            tray.Icon = Icon ?? SystemIcons.Shield; tray.Text = "C2Guard"; tray.Visible = true;
            tray.DoubleClick += (s, e) => { Show(); WindowState = FormWindowState.Normal; Activate(); };
            Resize += (s, e) => { if (WindowState == FormWindowState.Minimized) { Hide(); tray.ShowBalloonTip(2000, "C2Guard", "通知領域で見張りを続けています", ToolTipIcon.Info); } };
            FormClosing += (s, e) => { tray.Visible = false; SaveState(); };

            timer.Tick += async (s, e) => await ScanAsync(false);
            Shown += async (s, e) =>
            {
                feeds.LoadCached();
                RefreshLists();
                if (feeds.Map.Count == 0 || DateTime.UtcNow - feeds.LastUpdate > TimeSpan.FromHours(6)) await UpdateFeedsAsync(false);
                await ScanAsync(true);
                timer.Enabled = state.AutoWatch;
            };
        }

        static void AddButton(Control parent, string t, EventHandler h)
        {
            var b = new Button { Text = t, AutoSize = true, Margin = new Padding(2, 0, 6, 0) };
            b.Click += h; parent.Controls.Add(b);
        }

        static void AddTab(TabControl tabs, string t, Control c)
        {
            var p = new TabPage(t); p.Controls.Add(c); tabs.TabPages.Add(p);
        }

        static Control WithButton(Control list, string text, EventHandler h)
        {
            var p = new Panel { Dock = DockStyle.Fill };
            var b = new Button { Text = text, Dock = DockStyle.Bottom, Height = 32 };
            b.Click += h;
            p.Controls.Add(list); p.Controls.Add(b);
            return p;
        }

        void Log(string msg) { state.AddLog(msg); logBox.AppendText(state.Log[state.Log.Count - 1] + "\r\n"); }
        void SaveState() { try { state.Save(); } catch { } }

        void SetStatus()
        {
            string upd = feeds.LastUpdate == DateTime.MinValue ? "未取得" : feeds.LastUpdate.ToLocalTime().ToString("M/d HH:mm");
            status.Text = "C2 リスト " + feeds.Map.Count + " 件（更新 " + upd + "）  ・  ブロック中 " + state.Blocked.Count +
                          "  ・  放置 " + state.Ignored.Count + (Sys.IsAdmin() ? "" : "  ・  管理者でないためブロックできません");
        }

        async Task UpdateFeedsAsync(bool manual)
        {
            if (updating) return;
            updating = true;
            status.Text = "C2 リストをダウンロードしています…";
            try
            {
                await Task.Run(() => feeds.Download(Feeds.HttpFetch));
                Log("C2 リストを更新: " + feeds.Map.Count + " 件" + (feeds.Errors.Count > 0 ? "（取得失敗 " + feeds.Errors.Count + " 件）" : ""));
                foreach (var e in feeds.Errors) Log("  取得失敗 " + e);
                if (manual && feeds.Errors.Count == Feeds.Sources.Length)
                    MessageBox.Show(this, "C2 リストを取得できませんでした。インターネット接続を確認してください。\n前回のリストで見張りを続けます。", "C2Guard", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            finally { updating = false; SetStatus(); }
        }

        async Task ScanAsync(bool manual)
        {
            if (scanning) return;
            scanning = true;
            try
            {
                List<Conn> conns;
                try { conns = await Task.Run(() => Sys.TcpConnections()); }
                catch (Exception e) { Log("通信一覧を取得できません: " + e.Message); return; }
                var hits = feeds.Match(conns);
                ShowConnections(conns, hits);
                if (DateTime.UtcNow - feeds.LastUpdate > TimeSpan.FromHours(6) && !updating) UpdateFeedsAsync(false).ContinueWith(t => { });
                foreach (var h in hits.GroupBy(x => x.Conn.RemoteIp).Select(g => g.First()))
                {
                    string ip = h.Conn.RemoteIp;
                    if (state.IsBlocked(ip))
                    {
                        // ルールを入れたのにまだ通信が残っている (ブロック前からつながっていた場合など)
                        if (notedBlocked.Add(ip)) { AddHitRow(h, "ブロック済み"); Log("ブロック済みの相手との通信が残っています: " + h.Conn.ProcessName + " → " + ip + "（PC の再起動かプロセスの終了で切れます）"); }
                        continue;
                    }
                    if (state.IsIgnored(ip) || sessionSkip.Contains(ip) || asking.Contains(ip)) continue;
                    asking.Add(ip);
                    Log("検出: " + h.Conn.ProcessName + " (PID " + h.Conn.Pid + ") → " + ip + ":" + h.Conn.RemotePort + "  " + h.Ind.Label);
                    tray.ShowBalloonTip(5000, "C2Guard: 危険な通信を検出", h.Conn.ProcessName + " → " + ip, ToolTipIcon.Warning);
                    if (!Visible) Show();
                    WindowState = FormWindowState.Normal; Activate();
                    using (var d = new HitDialog(h))
                    {
                        var r = d.ShowDialog(this);
                        if (r != DialogResult.OK) { sessionSkip.Add(ip); AddHitRow(h, "保留"); Log("  保留 (確認画面を閉じた)"); }
                        else if (d.Block) DoBlock(h, d.KillProcess);
                        else
                        {
                            state.Ignored.Add(new State.Entry { Ip = ip, Label = h.Ind.Label, Process = h.Conn.ProcessName, Time = DateTime.Now.ToString("yyyy-MM-dd HH:mm") });
                            AddHitRow(h, "放置"); Log("  放置にしました: " + ip);
                        }
                    }
                    asking.Remove(ip);
                    SaveState(); RefreshLists();
                }
                if (manual && hits.Count == 0) Log("スキャン: 通信 " + conns.Count + " 本、C2 リストに一致なし");
            }
            finally { scanning = false; SetStatus(); }
        }

        void DoBlock(Hit h, bool kill)
        {
            string ip = h.Conn.RemoteIp;
            if (!Sys.IsAdmin())
            {
                var r = MessageBox.Show(this, "ブロックには管理者権限が必要です。\n管理者として起動し直しますか？\n（起動し直したあと、同じ通信が続いていれば再度確認します）",
                    "C2Guard", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                AddHitRow(h, "未ブロック");
                Log("  ブロックできません (管理者でない): " + ip);
                if (r == DialogResult.Yes) RestartAsAdmin();
                return;
            }
            bool ok = true; string err = "";
            foreach (string a in FirewallArgs.Add(ip))
            {
                var res = Sys.Netsh(a);
                if (!res.Item1) { ok = false; err = res.Item2; }
            }
            if (!ok) { Log("  ブロック失敗: " + ip + "  " + err); AddHitRow(h, "失敗"); MessageBox.Show(this, "ファイアウォールの設定に失敗しました。\n" + err, "C2Guard"); return; }
            state.Blocked.Add(new State.Entry { Ip = ip, Label = h.Ind.Label, Process = h.Conn.ProcessName, Time = DateTime.Now.ToString("yyyy-MM-dd HH:mm") });
            Log("  ブロックしました: " + ip + "（ファイアウォール規則 " + FirewallArgs.RuleName(ip) + "）");
            AddHitRow(h, "ブロック");
            if (kill && h.Conn.Pid > 4)
            {
                try { Process.GetProcessById(h.Conn.Pid).Kill(); Log("  プロセスを終了しました: " + h.Conn.ProcessName + " (PID " + h.Conn.Pid + ")"); }
                catch (Exception e) { Log("  プロセスを終了できません: " + e.Message); }
            }
        }

        void Unblock()
        {
            foreach (ListViewItem it in blockedView.SelectedItems)
            {
                string ip = it.Text;
                if (Sys.IsAdmin())
                {
                    var res = Sys.Netsh(FirewallArgs.Delete(ip));
                    if (!res.Item1 && !res.Item2.Contains("No rules") && !res.Item2.Contains("一致する規則")) { Log("解除失敗: " + ip + " " + res.Item2); continue; }
                }
                else { MessageBox.Show(this, "解除には管理者権限が必要です。", "C2Guard"); return; }
                state.Blocked.RemoveAll(e => e.Ip == ip);
                Log("ブロックを解除: " + ip);
            }
            SaveState(); RefreshLists(); SetStatus();
        }

        void Unignore()
        {
            foreach (ListViewItem it in ignoredView.SelectedItems) { state.Ignored.RemoveAll(e => e.Ip == it.Text); Log("放置リストから外しました: " + it.Text); }
            SaveState(); RefreshLists(); SetStatus();
        }

        void AddHitRow(Hit h, string action)
        {
            var it = new ListViewItem(new[] { DateTime.Now.ToString("MM/dd HH:mm:ss"), h.Conn.ProcessName, h.Conn.Pid.ToString(),
                h.Conn.RemoteIp + ":" + h.Conn.RemotePort, h.Ind.Label, action });
            if (action == "ブロック" || action == "ブロック済み") it.ForeColor = Color.DarkGreen;
            else if (action == "放置") it.ForeColor = Color.DimGray;
            else it.ForeColor = Color.Firebrick;
            hitsView.Items.Insert(0, it);
            while (hitsView.Items.Count > 300) hitsView.Items.RemoveAt(hitsView.Items.Count - 1);
        }

        void ShowConnections(List<Conn> conns, List<Hit> hits)
        {
            var bad = new HashSet<string>(hits.Select(h => h.Conn.RemoteIp));
            connView.BeginUpdate();
            connView.Items.Clear();
            foreach (var c in conns.OrderByDescending(c => bad.Contains(c.RemoteIp)).ThenBy(c => c.ProcessName))
            {
                string verdict = bad.Contains(c.RemoteIp) ? "⚠ C2 リストに一致: " + feeds.Map[c.RemoteIp].Label
                    : Feeds.IsPrivate(c.RemoteIp) ? "LAN / 自分" : "リストに無し";
                var it = new ListViewItem(new[] { c.ProcessName, c.Pid.ToString(), c.RemoteIp + ":" + c.RemotePort, c.State, verdict });
                if (bad.Contains(c.RemoteIp)) { it.ForeColor = Color.White; it.BackColor = Color.Firebrick; }
                it.ToolTipText = c.ProcessPath;
                connView.Items.Add(it);
            }
            connView.EndUpdate();
        }

        void RefreshLists()
        {
            Fill(blockedView, state.Blocked); Fill(ignoredView, state.Ignored);
            logBox.Text = string.Join("\r\n", state.Log) + (state.Log.Count > 0 ? "\r\n" : "");
            SetStatus();
        }

        static void Fill(ListView lv, List<State.Entry> list)
        {
            lv.BeginUpdate(); lv.Items.Clear();
            foreach (var e in list) lv.Items.Add(new ListViewItem(new[] { e.Ip, e.Label ?? "", e.Process ?? "", e.Time ?? "" }));
            lv.EndUpdate();
        }

        void OpenCustom()
        {
            if (!File.Exists(feeds.CustomPath))
                File.WriteAllText(feeds.CustomPath, "# 自分で追加したい C2 の IP を 1 行に 1 個書く (# から後はメモ)\r\n# 例: 203.0.113.10 テスト用\r\n", new UTF8Encoding(true));
            try { Process.Start(new ProcessStartInfo("notepad.exe", "\"" + feeds.CustomPath + "\"") { UseShellExecute = true }); } catch { }
            MessageBox.Show(this, "保存したら「リストを更新」を押すと反映されます。", "C2Guard");
        }

        void RestartAsAdmin()
        {
            try
            {
                Process.Start(new ProcessStartInfo(Application.ExecutablePath) { UseShellExecute = true, Verb = "runas" });
                SaveState(); tray.Visible = false;
                Environment.Exit(0);
            }
            catch { /* UAC で「いいえ」 */ }
        }
    }
}
