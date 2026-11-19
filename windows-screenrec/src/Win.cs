using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;

[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.6.2", FrameworkDisplayName = ".NET Framework 4.6.2")]
[assembly: System.Reflection.AssemblyTitle("画面録画")]
[assembly: System.Reflection.AssemblyProduct("ScreenRec")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace ScreenRec
{
    static class Program
    {
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--selftest")
                return SelfTest.Run(args.Length > 1 ? args[1] : Path.Combine(Path.GetTempPath(), "screenrec-test"));
            // 拡大表示 (125% など) の PC でも、画面の本当の画素数で取り込むため
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
            return 0;
        }
    }

    /// <summary>Windows の画面を取り込む (マウスカーソル付き)</summary>
    static class ScreenGrab
    {
        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
        [StructLayout(LayoutKind.Sequential)] struct CURSORINFO { public int cbSize, flags; public IntPtr hCursor; public POINT pt; }
        [StructLayout(LayoutKind.Sequential)] struct ICONINFO { public bool fIcon; public int xHotspot, yHotspot; public IntPtr hbmMask, hbmColor; }
        [DllImport("user32.dll")] static extern bool GetCursorInfo(ref CURSORINFO ci);
        [DllImport("user32.dll")] static extern bool GetIconInfo(IntPtr h, out ICONINFO ii);
        [DllImport("user32.dll")] static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr icon, int w, int h, int step, IntPtr brush, int flags);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);

        /// <summary>画面の area を取り込み、outSize に縮めた絵を返す。</summary>
        public static Bitmap Grab(Rectangle area, Size outSize, bool cursor)
        {
            var full = new Bitmap(area.Width, area.Height, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
            using (var g = Graphics.FromImage(full))
            {
                g.CopyFromScreen(area.Location, Point.Empty, area.Size, CopyPixelOperation.SourceCopy);
                if (cursor) DrawCursor(g, area.Location);
            }
            if (outSize == area.Size) return full;
            var small = new Bitmap(outSize.Width, outSize.Height, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
            using (var g = Graphics.FromImage(small))
            {
                g.InterpolationMode = InterpolationMode.Bilinear;
                g.PixelOffsetMode = PixelOffsetMode.HighSpeed;
                g.DrawImage(full, new Rectangle(Point.Empty, outSize));
            }
            full.Dispose();
            return small;
        }

        static void DrawCursor(Graphics g, Point origin)
        {
            var ci = new CURSORINFO { cbSize = Marshal.SizeOf(typeof(CURSORINFO)) };
            if (!GetCursorInfo(ref ci) || (ci.flags & 1) == 0 || ci.hCursor == IntPtr.Zero) return;
            int hx = 0, hy = 0;
            ICONINFO ii;
            if (GetIconInfo(ci.hCursor, out ii))
            {
                hx = ii.xHotspot; hy = ii.yHotspot;
                if (ii.hbmMask != IntPtr.Zero) DeleteObject(ii.hbmMask);
                if (ii.hbmColor != IntPtr.Zero) DeleteObject(ii.hbmColor);
            }
            IntPtr hdc = g.GetHdc();
            try { DrawIconEx(hdc, ci.pt.X - hx - origin.X, ci.pt.Y - hy - origin.Y, ci.hCursor, 0, 0, 0, IntPtr.Zero, 3 /* DI_NORMAL */); }
            finally { g.ReleaseHdc(hdc); }
        }
    }

    class MainForm : Form
    {
        [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr h, int id, int mods, int vk);
        [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr h, int id);
        const int HOTKEY_REC = 1, HOTKEY_PAUSE = 2, WM_HOTKEY = 0x0312;

        readonly ComboBox screenBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 300 };
        readonly ComboBox fpsBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 120 };
        readonly ComboBox qualityBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 120 };
        readonly ComboBox sizeBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 200 };
        readonly CheckBox cursorBox = new CheckBox { Text = "マウスカーソルも録る", AutoSize = true, Checked = true };
        readonly CheckBox hideBox = new CheckBox { Text = "録画中はこの画面を隠す（通知領域に入る）", AutoSize = true, Checked = true };
        readonly TextBox folderBox = new TextBox { Width = 300, ReadOnly = true };
        readonly Button recBtn = new Button { Text = "● 録画開始  (F9)", Width = 200, Height = 44 };
        readonly Button pauseBtn = new Button { Text = "一時停止  (F10)", Width = 140, Height = 44, Enabled = false };
        readonly Label timeLbl = new Label { Text = "00:00:00", AutoSize = true, Font = new Font("Consolas", 20f, FontStyle.Bold) };
        readonly Label infoLbl = new Label { AutoSize = true, ForeColor = Color.DimGray };
        readonly NotifyIcon tray = new NotifyIcon();
        readonly Timer ui = new Timer { Interval = 250 };
        Recorder rec;
        int countdown;

        static readonly int[] FpsValues = { 10, 15, 20, 30 };
        static readonly int[] QualityValues = { 85, 70, 50 };

        public MainForm()
        {
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96f, 96f); AutoScaleMode = AutoScaleMode.Dpi;
            Text = "画面録画"; Font = new Font("Yu Gothic UI", 10f);
            FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoSize = true; AutoSizeMode = AutoSizeMode.GrowAndShrink; Padding = new Padding(10);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var main = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false };
            var grid = new TableLayoutPanel { AutoSize = true, ColumnCount = 2 };
            AddRow(grid, "録画する画面", screenBox);
            AddRow(grid, "なめらかさ", fpsBox);
            AddRow(grid, "画質", qualityBox);
            AddRow(grid, "動画の大きさ", sizeBox);
            var folderRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0) };
            var change = new Button { Text = "変更", AutoSize = true };
            change.Click += (s, e) => ChooseFolder();
            folderRow.Controls.Add(folderBox); folderRow.Controls.Add(change);
            AddRow(grid, "保存先", folderRow);
            grid.Controls.Add(cursorBox, 1, grid.RowCount); grid.RowCount++;
            grid.Controls.Add(hideBox, 1, grid.RowCount); grid.RowCount++;
            main.Controls.Add(grid);

            var bottom = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 10, 0, 0) };
            recBtn.Click += (s, e) => ToggleRecord();
            pauseBtn.Click += (s, e) => TogglePause();
            var open = new Button { Text = "保存先を開く", Width = 130, Height = 44 };
            open.Click += (s, e) => { try { Directory.CreateDirectory(folderBox.Text); Process.Start("explorer.exe", "\"" + folderBox.Text + "\""); } catch { } };
            bottom.Controls.Add(recBtn); bottom.Controls.Add(pauseBtn); bottom.Controls.Add(open);
            main.Controls.Add(bottom);
            main.Controls.Add(timeLbl);
            infoLbl.MaximumSize = new Size(500, 0);
            main.Controls.Add(infoLbl);
            Controls.Add(main);
            ResumeLayout(false); PerformLayout();

            foreach (var sc in Screen.AllScreens.Select((sc, i) => new { sc, i }))
                screenBox.Items.Add("画面 " + (sc.i + 1) + "  (" + sc.sc.Bounds.Width + "×" + sc.sc.Bounds.Height + ")" + (sc.sc.Primary ? "  メイン" : ""));
            if (Screen.AllScreens.Length > 1) screenBox.Items.Add("すべての画面をまとめて");
            screenBox.SelectedIndex = Math.Max(0, Array.FindIndex(Screen.AllScreens, s => s.Primary));
            fpsBox.Items.AddRange(new object[] { "10 fps (軽い)", "15 fps", "20 fps", "30 fps (なめらか)" }); fpsBox.SelectedIndex = 2;
            qualityBox.Items.AddRange(new object[] { "高", "中", "低 (小さいファイル)" }); qualityBox.SelectedIndex = 1;
            sizeBox.Items.AddRange(new object[] { "そのまま", "横 1280 に縮小", "横 960 に縮小" }); sizeBox.SelectedIndex = 0;
            folderBox.Text = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "画面録画");
            infoLbl.Text = "F9 で録画開始 / 停止（ほかのアプリを使っていても効きます）";

            tray.Icon = Icon ?? SystemIcons.Application; tray.Text = "画面録画";
            tray.DoubleClick += (s, e) => ShowMe();
            var menu = new ContextMenuStrip();
            menu.Items.Add("録画を止める", null, (s, e) => { if (rec != null) ToggleRecord(); });
            menu.Items.Add("一時停止 / 再開", null, (s, e) => TogglePause());
            menu.Items.Add("画面を表示", null, (s, e) => ShowMe());
            tray.ContextMenuStrip = menu;

            ui.Tick += (s, e) => Tick();
            Load += (s, e) =>
            {
                try
                {
                    if (!RegisterHotKey(Handle, HOTKEY_REC, 0, (int)Keys.F9)) infoLbl.Text = "F9 はほかのアプリが使っているため使えません（ボタンで操作してください）";
                    RegisterHotKey(Handle, HOTKEY_PAUSE, 0, (int)Keys.F10);
                }
                catch (EntryPointNotFoundException) { }
            };
            FormClosing += (s, e) =>
            {
                if (rec != null && MessageBox.Show(this, "録画中です。止めて終了しますか？", "画面録画", MessageBoxButtons.YesNo) != DialogResult.Yes) { e.Cancel = true; return; }
                if (rec != null) StopRecord();
                try { UnregisterHotKey(Handle, HOTKEY_REC); UnregisterHotKey(Handle, HOTKEY_PAUSE); } catch (EntryPointNotFoundException) { }
                tray.Visible = false;
            };
        }

        static void AddRow(TableLayoutPanel t, string label, Control c)
        {
            t.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 8, 12, 4) }, 0, t.RowCount);
            c.Margin = new Padding(0, 4, 0, 4);
            t.Controls.Add(c, 1, t.RowCount);
            t.RowCount++;
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_HOTKEY)
            {
                int id = m.WParam.ToInt32();
                if (id == HOTKEY_REC) ToggleRecord();
                else if (id == HOTKEY_PAUSE) TogglePause();
            }
            base.WndProc(ref m);
        }

        void ChooseFolder()
        {
            using (var d = new FolderBrowserDialog { Description = "録画を保存するフォルダー", SelectedPath = folderBox.Text })
                if (d.ShowDialog(this) == DialogResult.OK) folderBox.Text = d.SelectedPath;
        }

        Rectangle Area()
        {
            var all = Screen.AllScreens;
            if (screenBox.SelectedIndex >= 0 && screenBox.SelectedIndex < all.Length) return all[screenBox.SelectedIndex].Bounds;
            var r = all[0].Bounds;
            foreach (var s in all) r = Rectangle.Union(r, s.Bounds);
            return r;
        }

        static Size OutSize(Rectangle area, int maxW)
        {
            int w = area.Width, h = area.Height;
            if (maxW > 0 && w > maxW) { h = (int)Math.Round(h * (double)maxW / w); w = maxW; }
            return new Size(w & ~1, h & ~1);       // 偶数にそろえる (再生ソフトの相性のため)
        }

        void ToggleRecord()
        {
            if (countdown > 0) { countdown = 0; ui.Stop(); SetIdle(); return; }   // カウントダウン中は取り消し
            if (rec != null) { StopRecord(); return; }
            countdown = 3;
            recBtn.Text = "取り消し"; SetSettingsEnabled(false);
            ui.Start(); Tick();
        }

        void BeginRecord()
        {
            var area = Area();
            var outSize = OutSize(area, sizeBox.SelectedIndex == 1 ? 1280 : sizeBox.SelectedIndex == 2 ? 960 : 0);
            if (sizeBox.SelectedIndex == 0) area = new Rectangle(area.Location, outSize);   // 縮小なし: 端の 1 画素を切って偶数に
            bool cursor = cursorBox.Checked;
            string name = "録画_" + DateTime.Now.ToString("yyyyMMdd_HHmmss");
            try
            {
                rec = new Recorder(() => ScreenGrab.Grab(area, outSize, cursor), outSize, FpsValues[fpsBox.SelectedIndex],
                                   QualityValues[qualityBox.SelectedIndex], folderBox.Text, name);
                rec.Start();
            }
            catch (Exception e)
            {
                rec = null; SetIdle();
                MessageBox.Show(this, "録画を始められませんでした。\n" + e.Message, "画面録画", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            recBtn.Text = "■ 停止  (F9)"; pauseBtn.Enabled = true;
            tray.Visible = true; tray.Text = "画面録画: 録画中";
            if (hideBox.Checked) Hide();
            tray.ShowBalloonTip(1500, "画面録画", "録画を始めました。F9 で停止します。", ToolTipIcon.Info);
        }

        void StopRecord()
        {
            var r = rec; rec = null;
            ui.Stop();
            r.Stop();
            SetIdle();
            ShowMe();
            if (r.Error != null)
                MessageBox.Show(this, "録画中にエラーが起きたため止めました（そこまでの分は保存されています）。\n" + r.Error.Message, "画面録画", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            infoLbl.Text = "保存しました: " + Path.GetFileName(r.Files[0]) + (r.Files.Count > 1 ? " ほか " + (r.Files.Count - 1) + " 個" : "") +
                           "（" + FormatSize(r.Bytes) + "）";
            if (r.Captured > 0 && r.Frames > r.Captured * 1.5)
                infoLbl.Text += "\n※ PC の処理が追いつかずカクつきました。fps を下げるか動画を縮小すると改善します";
        }

        void TogglePause()
        {
            if (rec == null) return;
            rec.Pause(!rec.Paused);
            pauseBtn.Text = rec.Paused ? "再開  (F10)" : "一時停止  (F10)";
            tray.Text = rec.Paused ? "画面録画: 一時停止中" : "画面録画: 録画中";
            tray.ShowBalloonTip(1000, "画面録画", rec.Paused ? "一時停止しました" : "再開しました", ToolTipIcon.Info);
        }

        void Tick()
        {
            if (countdown > 0)
            {
                timeLbl.Text = "あと " + countdown + " 秒";
                countdown--;
                if (countdown == 0) { ui.Interval = 1000; BeginInvoke((Action)(() => { BeginRecord(); ui.Interval = 250; ui.Start(); })); ui.Stop(); }
                else ui.Interval = 1000;
                return;
            }
            if (rec == null) return;
            timeLbl.Text = rec.Elapsed.ToString(@"hh\:mm\:ss");
            infoLbl.Text = FormatSize(rec.Bytes) + (rec.Paused ? "  一時停止中" : "") + (rec.Files.Count > 1 ? "  (" + rec.Files.Count + " 個目のファイル)" : "");
            tray.Text = "画面録画 " + timeLbl.Text;
            if (rec.Error != null) StopRecord();
        }

        static string FormatSize(long b)
        {
            return b < 1024 * 1024 ? (b / 1024) + " KB" : b < 1024L * 1024 * 1024 ? (b / 1024.0 / 1024).ToString("0.0") + " MB" : (b / 1024.0 / 1024 / 1024).ToString("0.00") + " GB";
        }

        void SetSettingsEnabled(bool on)
        {
            foreach (Control c in new Control[] { screenBox, fpsBox, qualityBox, sizeBox, cursorBox, hideBox }) c.Enabled = on;
        }

        void SetIdle()
        {
            recBtn.Text = "● 録画開始  (F9)"; pauseBtn.Text = "一時停止  (F10)"; pauseBtn.Enabled = false;
            SetSettingsEnabled(true); timeLbl.Text = "00:00:00"; tray.Visible = false; ui.Interval = 250;
        }

        void ShowMe() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    }
}
