using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace HtmlHome
{
    /// <summary>
    /// ウィンドウ本体。WebView2 を 2 枚持つ:
    ///   home    … ランチャー画面 (launcher/index.html)。ずっと生きている。
    ///   appView … 追加されたアプリを表示する。アプリごとに別の仮想ホスト名を割り当て、
    ///             セーブデータ (localStorage など) がアプリ間で混ざらないようにする。
    /// </summary>
    public class MainForm : Form
    {
        const string HomeHost = "home" + Store.HostSuffix;
        const string HomeUrl = "https://" + HomeHost + "/index.html";

        readonly Store store;
        readonly WebView2 home = new WebView2();
        readonly WebView2 appView = new WebView2();
        readonly FlowLayoutPanel topBar = new FlowLayoutPanel();
        readonly Button backBtn = new Button();
        readonly Button reloadBtn = new Button();
        readonly Label titleLabel = new Label();
        readonly SemaphoreSlim originGate = new SemaphoreSlim(1, 1);
        readonly HashSet<string> mapped = new HashSet<string>();

        string currentApp;            // 表示中のアプリ id (ホーム表示中は null)
        string pendingToken;          // RunInOrigin の返事待ち
        TaskCompletionSource<string> pendingResult;

        public MainForm(Store store)
        {
            this.store = store;
            SuspendLayout();
            // 96dpi 基準で組み、実際の画面倍率に合わせて WinForms に拡大させる
            AutoScaleDimensions = new SizeF(96f, 96f);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = "HTMLホーム";
            ClientSize = new Size(1100, 720);
            MinimumSize = new Size(640, 480);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(20, 22, 28);
            Font = new Font("Yu Gothic UI", 10f);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            topBar.Dock = DockStyle.Top; topBar.Height = 42; topBar.Visible = false;
            topBar.WrapContents = false; topBar.Padding = new Padding(6, 5, 6, 5);
            topBar.BackColor = Color.FromArgb(32, 35, 43);
            StyleButton(backBtn, "← ホーム");
            StyleButton(reloadBtn, "再読み込み");
            titleLabel.AutoSize = true; titleLabel.ForeColor = Color.FromArgb(238, 240, 245);
            titleLabel.Anchor = AnchorStyles.Left; titleLabel.Margin = new Padding(12, 0, 0, 0);
            topBar.Controls.Add(backBtn); topBar.Controls.Add(reloadBtn); topBar.Controls.Add(titleLabel);
            backBtn.Click += async (s, e) => await CloseAppAsync();
            reloadBtn.Click += (s, e) => { try { if (currentApp != null) appView.CoreWebView2.Reload(); } catch { } };

            home.Dock = DockStyle.Fill; appView.Dock = DockStyle.Fill; appView.Visible = false;
            home.DefaultBackgroundColor = Color.FromArgb(20, 22, 28);
            // Dock は後ろに追加したものから先に配置されるので、Fill → Top の順に入れる
            Controls.Add(home); Controls.Add(appView); Controls.Add(topBar);

            ResumeLayout(false);
            PerformLayout();
            Load += async (s, e) => await InitAsync();
            FormClosing += (s, e) => { try { store.Save(); } catch { } };
        }

        void StyleButton(Button b, string text)
        {
            b.Text = text; b.AutoSize = true; b.AutoSizeMode = AutoSizeMode.GrowAndShrink; b.FlatStyle = FlatStyle.Flat;
            b.ForeColor = Color.FromArgb(238, 240, 245); b.BackColor = Color.FromArgb(42, 46, 56);
            b.FlatAppearance.BorderColor = Color.FromArgb(58, 63, 76);
            b.Padding = new Padding(8, 2, 8, 2); b.Margin = new Padding(2, 0, 4, 0);
            b.Anchor = AnchorStyles.Left; b.TabStop = false;
        }

        async Task InitAsync()
        {
            try
            {
                CoreWebView2Environment.SetLoaderDllFolderPath(Program.ExtractLoader(store.Root));
                var env = await CoreWebView2Environment.CreateAsync(null, store.WebViewDir, null);
                await home.EnsureCoreWebView2Async(env);
                await appView.EnsureCoreWebView2Async(env);
            }
            catch (Exception ex)
            {
                var r = MessageBox.Show(this,
                    "画面を表示する部品 (Microsoft Edge WebView2 ランタイム) を起動できませんでした。\n\n" +
                    "Windows 11 には最初から入っています。Windows 10 で入っていない場合は、\n" +
                    "Microsoft の配布ページからインストールしてください。\n\n" +
                    "配布ページを開きますか？\n\n詳細: " + ex.Message,
                    "HTMLホーム", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                if (r == DialogResult.Yes) OpenExternal("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
                Close();
                return;
            }

            var hs = home.CoreWebView2.Settings;
            hs.AreDefaultContextMenusEnabled = false; hs.AreDevToolsEnabled = false;
            hs.IsStatusBarEnabled = false; hs.IsZoomControlEnabled = false;
            home.CoreWebView2.SetVirtualHostNameToFolderMapping(HomeHost, store.LauncherDir, CoreWebView2HostResourceAccessKind.Deny);
            home.CoreWebView2.WebMessageReceived += OnHomeMessage;
            home.CoreWebView2.NewWindowRequested += (s, e) => { e.Handled = true; };
            // ランチャー画面は自分のページ以外へ移動させない
            home.CoreWebView2.NavigationStarting += (s, e) => { if (!e.Uri.StartsWith("https://" + HomeHost + "/", StringComparison.OrdinalIgnoreCase)) e.Cancel = true; };

            appView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            appView.CoreWebView2.WebMessageReceived += OnAppMessage;
            appView.CoreWebView2.WebResourceResponseReceived += OnAppResponse;
            appView.CoreWebView2.NewWindowRequested += (s, e) => { e.Handled = true; OpenExternal(e.Uri); };

            home.CoreWebView2.Navigate(HomeUrl);
        }

        static void OpenExternal(string url)
        {
            Uri u;
            if (!Uri.TryCreate(url, UriKind.Absolute, out u)) return;
            if (u.Scheme != "http" && u.Scheme != "https") return;
            if (u.Host.EndsWith(Store.HostSuffix, StringComparison.OrdinalIgnoreCase)) return;
            try { Process.Start(new ProcessStartInfo(u.AbsoluteUri) { UseShellExecute = true }); } catch { }
        }

        // ------------------------------------------------------------------
        //  ランチャー画面からの命令
        // ------------------------------------------------------------------
        async void OnHomeMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            if (!e.Source.StartsWith("https://" + HomeHost + "/", StringComparison.OrdinalIgnoreCase)) return;
            object id = null;
            var reply = new Dictionary<string, object>();
            try
            {
                var msg = Store.Json().Deserialize<Dictionary<string, object>>(e.TryGetWebMessageAsString());
                msg.TryGetValue("id", out id);
                reply["id"] = id;
                object a;
                var args = msg.TryGetValue("args", out a) ? a as Dictionary<string, object> : null;
                reply["result"] = await HandleCommand(Convert.ToString(msg["cmd"]), args ?? new Dictionary<string, object>());
                reply["ok"] = true;
            }
            catch (Exception ex)
            {
                reply["id"] = id; reply["ok"] = false; reply["error"] = ex.Message;
            }
            try { home.CoreWebView2.PostWebMessageAsString(Store.Json().Serialize(reply)); } catch { }
        }

        static string Str(Dictionary<string, object> d, string k)
        {
            object v; return d.TryGetValue(k, out v) && v != null ? Convert.ToString(v) : "";
        }

        async Task<object> HandleCommand(string cmd, Dictionary<string, object> args)
        {
            switch (cmd)
            {
                case "getState":
                    return new Dictionary<string, object> { { "apps", store.Apps }, { "ui", store.UiJson }, { "version", Program.Version } };

                case "saveUi":
                    store.UiJson = Str(args, "json");
                    return null;

                case "installApp":
                    {
                        var assets = new List<KeyValuePair<string, byte[]>>();
                        object raw;
                        if (args.TryGetValue("assets", out raw) && raw is IEnumerable)
                            foreach (object o in (IEnumerable)raw)
                            {
                                var d = o as Dictionary<string, object>;
                                if (d == null) continue;
                                assets.Add(new KeyValuePair<string, byte[]>(Str(d, "name"), Convert.FromBase64String(Str(d, "b64"))));
                            }
                        return store.Install(Str(args, "name"), Str(args, "html"), Str(args, "icon"), assets);
                    }

                case "uninstallApp":
                    {
                        string id = Str(args, "id");
                        if (Store.IsBuiltinId(id)) throw new InvalidOperationException("最初からあるアプリは消せません");
                        if (store.Find(id) == null) throw new InvalidOperationException("アプリが見つかりません");
                        try { await ClearDataAsync(id); } catch { }
                        await ParkAsync();
                        Unmap(id);
                        store.Uninstall(id);
                        return null;
                    }

                case "launchApp":
                    Launch(Str(args, "id"));
                    return null;

                case "measureApp":
                    {
                        string id = Str(args, "id");
                        if (store.Find(id) == null) throw new InvalidOperationException("アプリが見つかりません");
                        long n = await MeasureAsync(id);
                        store.Save();
                        return new Dictionary<string, object> { { "dataSize", n } };
                    }

                case "clearData":
                    {
                        string id = Str(args, "id");
                        if (store.Find(id) == null) throw new InvalidOperationException("アプリが見つかりません");
                        await ClearDataAsync(id);
                        store.SetDataSize(id, 0); store.Save();
                        return null;
                    }

                case "resetTraffic":
                    store.ResetTraffic();
                    return null;

                case "speedTest":
                    return await Task.Run(() => SpeedTest.Run());

                case "exportBackup":
                    return await ExportAsync();

                case "importBackup":
                    return await ImportAsync();

                case "exit":
                    BeginInvoke((Action)Close);
                    return null;
            }
            throw new InvalidOperationException("不明な命令: " + cmd);
        }

        // ------------------------------------------------------------------
        //  アプリの表示
        // ------------------------------------------------------------------
        void Map(string id)
        {
            if (mapped.Contains(id)) return;
            appView.CoreWebView2.SetVirtualHostNameToFolderMapping(Store.HostOf(id), store.AppDir(id), CoreWebView2HostResourceAccessKind.Allow);
            mapped.Add(id);
        }
        void Unmap(string id)
        {
            if (!mapped.Remove(id)) return;
            try { appView.CoreWebView2.ClearVirtualHostNameToFolderMapping(Store.HostOf(id)); } catch { }
        }

        void Launch(string id)
        {
            var meta = store.Find(id);
            if (meta == null) throw new InvalidOperationException("アプリが見つかりません");
            if (currentApp != null) return;
            Map(id);
            currentApp = id;
            titleLabel.Text = meta.name;
            Text = meta.name + " — HTMLホーム";
            backBtn.Enabled = true;
            topBar.Visible = true; appView.Visible = true; home.Visible = false;
            appView.CoreWebView2.Navigate(Store.OriginOf(id) + "/index.html");
            appView.Focus();
        }

        async Task CloseAppAsync()
        {
            string id = currentApp;
            if (id == null) return;
            backBtn.Enabled = false;
            currentApp = null;                       // ここから先の通信は数えない
            try { await MeasureAsync(id); } catch { }
            try { appView.CoreWebView2.Navigate("about:blank"); } catch { }
            appView.Visible = false; topBar.Visible = false; home.Visible = true;
            Text = "HTMLホーム";
            try { store.Save(); } catch { }
            try { home.CoreWebView2.PostWebMessageAsString("{\"event\":\"refresh\"}"); } catch { }
            home.Focus();
        }

        /// <summary>アプリがインターネットから受け取った量を数える (自分のフォルダーから読んだ分は数えない)。</summary>
        async void OnAppResponse(object sender, CoreWebView2WebResourceResponseReceivedEventArgs e)
        {
            string id = currentApp;
            if (id == null) return;
            try
            {
                Uri u;
                if (!Uri.TryCreate(e.Request.Uri, UriKind.Absolute, out u)) return;
                if (u.Scheme != "http" && u.Scheme != "https") return;
                if (u.Host.EndsWith(Store.HostSuffix, StringComparison.OrdinalIgnoreCase)) return;
                long n = -1;
                var hd = e.Response.Headers;
                if (hd.Contains("Content-Length")) { long v; if (long.TryParse(hd.GetHeader("Content-Length"), out v)) n = v; }
                if (n < 0)
                {
                    using (Stream s = await e.Response.GetContentAsync()) n = s == null ? 0 : s.Length;
                }
                store.AddTraffic(id, n);
            }
            catch { /* 本文を取れない応答 (リダイレクトなど) は数えない */ }
        }

        // ------------------------------------------------------------------
        //  アプリのセーブデータを触る
        //  アプリと同じ仮想ホストの空ページ (__blank.html) を開いてスクリプトを実行する。
        //  アプリ本体のコードは動かさないので、返事を偽装されることがない。
        // ------------------------------------------------------------------
        void OnAppMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            var tcs = pendingResult; string token = pendingToken;
            if (tcs == null || token == null) return;
            try
            {
                var msg = Store.Json().Deserialize<Dictionary<string, object>>(e.TryGetWebMessageAsString());
                object t, v;
                if (msg == null || !msg.TryGetValue("__hh", out t) || Convert.ToString(t) != token) return;
                msg.TryGetValue("v", out v);
                tcs.TrySetResult(Store.Json().Serialize(v));
            }
            catch { }
        }

        async Task<string> RunInOrigin(string id, string body)
        {
            if (currentApp != null) throw new InvalidOperationException("アプリの表示中はできません");
            await originGate.WaitAsync();
            try
            {
                Map(id);
                string url = Store.OriginOf(id) + "/" + Store.BlankPage;
                var nav = new TaskCompletionSource<bool>();
                EventHandler<CoreWebView2NavigationCompletedEventArgs> onNav = (s, e) =>
                {
                    string src = ""; try { src = appView.CoreWebView2.Source; } catch { }
                    if (string.Equals(src, url, StringComparison.OrdinalIgnoreCase)) nav.TrySetResult(e.IsSuccess);
                };
                appView.CoreWebView2.NavigationCompleted += onNav;
                try
                {
                    appView.CoreWebView2.Navigate(url);
                    if (await Task.WhenAny(nav.Task, Task.Delay(8000)) != nav.Task || !nav.Task.Result)
                        throw new InvalidOperationException("アプリのデータ領域を開けませんでした");
                }
                finally { appView.CoreWebView2.NavigationCompleted -= onNav; }

                string token = Guid.NewGuid().ToString("N");
                var tcs = new TaskCompletionSource<string>();
                pendingToken = token; pendingResult = tcs;
                try
                {
                    await appView.ExecuteScriptAsync(Scripts.Wrap(body, token));
                    if (await Task.WhenAny(tcs.Task, Task.Delay(8000)) != tcs.Task)
                        throw new InvalidOperationException("アプリのデータ領域から返事がありませんでした");
                    return tcs.Task.Result;
                }
                finally { pendingToken = null; pendingResult = null; }
            }
            finally { originGate.Release(); }
        }

        async Task<long> MeasureAsync(string id)
        {
            var r = Store.Json().Deserialize<Dictionary<string, object>>(await RunInOrigin(id, Scripts.Measure));
            long n = r != null && r.ContainsKey("bytes") ? Convert.ToInt64(Convert.ToDouble(r["bytes"])) : 0;
            store.SetDataSize(id, n);
            return n;
        }

        async Task ClearDataAsync(string id)
        {
            await RunInOrigin(id, Scripts.Clear);
            try
            {
                var mgr = appView.CoreWebView2.CookieManager;
                foreach (var c in await mgr.GetCookiesAsync(Store.OriginOf(id) + "/")) mgr.DeleteCookie(c);
            }
            catch { }
        }

        /// <summary>appView を about:blank に移し、アプリのフォルダーを掴んでいない状態にする。</summary>
        async Task ParkAsync()
        {
            try
            {
                var nav = new TaskCompletionSource<bool>();
                EventHandler<CoreWebView2NavigationCompletedEventArgs> onNav = (s, e) =>
                {
                    string src = ""; try { src = appView.CoreWebView2.Source; } catch { }
                    if (src.StartsWith("about:blank", StringComparison.OrdinalIgnoreCase)) nav.TrySetResult(true);
                };
                appView.CoreWebView2.NavigationCompleted += onNav;
                try { appView.CoreWebView2.Navigate("about:blank"); await Task.WhenAny(nav.Task, Task.Delay(2000)); }
                finally { appView.CoreWebView2.NavigationCompleted -= onNav; }
            }
            catch { }
        }

        // ------------------------------------------------------------------
        //  バックアップ
        // ------------------------------------------------------------------
        async Task<object> ExportAsync()
        {
            string path;
            using (var dlg = new SaveFileDialog())
            {
                dlg.Title = "バックアップを書き出す";
                dlg.Filter = "HTMLホーム バックアップ (*.hhbak)|*.hhbak";
                dlg.FileName = "HtmlHome_" + DateTime.Now.ToString("yyyyMMdd_HHmm") + ".hhbak";
                if (dlg.ShowDialog(this) != DialogResult.OK) return new Dictionary<string, object> { { "cancelled", true } };
                path = dlg.FileName;
            }
            var save = new Dictionary<string, string>();
            foreach (var a in store.Apps)
            {
                try
                {
                    string json = await RunInOrigin(a.id, Scripts.Dump);
                    if (!string.IsNullOrEmpty(json) && json != "{}" && json != "null") save[a.id] = json;
                }
                catch { }
            }
            await Task.Run(() => store.ExportBackup(path, save));
            return new Dictionary<string, object> { { "path", path } };
        }

        async Task<object> ImportAsync()
        {
            string path;
            using (var dlg = new OpenFileDialog())
            {
                dlg.Title = "バックアップを読み込む";
                dlg.Filter = "HTMLホーム バックアップ (*.hhbak)|*.hhbak|すべてのファイル (*.*)|*.*";
                if (dlg.ShowDialog(this) != DialogResult.OK) return new Dictionary<string, object> { { "cancelled", true } };
                path = dlg.FileName;
            }
            // 壊れたファイルや別物を選んだときに今のデータを失わないよう、先に中身を確かめる
            if (!await Task.Run(() => Store.LooksLikeBackup(path)))
                throw new InvalidOperationException("HTMLホームのバックアップではありません");
            // 今あるアプリのセーブデータを消してから入れ替える (消さないと WebView 側に残り続ける)
            foreach (var a in store.Apps)
            {
                try { await ClearDataAsync(a.id); } catch { }
                Unmap(a.id);
            }
            await ParkAsync();
            Dictionary<string, string> save = await Task.Run(() => store.ImportBackup(path));
            var now = store.Apps;
            foreach (var a in now)
            {
                try
                {
                    string json;
                    if (save.TryGetValue(a.id, out json))
                    {
                        // ファイルの中身をそのままスクリプトに埋め込まず、いったん辞書として読み直す
                        var d = Store.Json().Deserialize<Dictionary<string, object>>(json);
                        if (d != null && d.Count > 0)
                            await RunInOrigin(a.id, Scripts.Restore(Store.Json().Serialize(d)));
                    }
                    await MeasureAsync(a.id);
                }
                catch { }
            }
            store.Save();
            try { appView.CoreWebView2.Navigate("about:blank"); } catch { }
            return new Dictionary<string, object> { { "ok", true }, { "count", now.Count } };
        }
    }
}
