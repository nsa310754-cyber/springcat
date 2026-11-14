using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

// これが無いと .NET Framework 4.8 上でも 4.0 互換の古い挙動 (TLS や DPI) で動いてしまう
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.6.2", FrameworkDisplayName = ".NET Framework 4.6.2")]
[assembly: System.Reflection.AssemblyTitle("HTMLホーム")]
[assembly: System.Reflection.AssemblyProduct("HtmlHome")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace HtmlHome
{
    static class Program
    {
        public const string Version = "1.0.0";

        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--selftest") return SelfTest.Run();
            if (args.Length > 0 && args[0] == "--dump-js")
            {
                // テスト用: データ領域で実行するスクリプトを JSON で出す (tests/scripts.test.mjs が使う)
                var tricky = new Dictionary<string, object> { { "score", "42" }, { "k</script>\u2028'\"", "v'\"\n<x>" } };
                Console.OutputEncoding = new System.Text.UTF8Encoding(false);
                Console.WriteLine(Store.Json().Serialize(new Dictionary<string, object>
                {
                    { "measure", Scripts.Wrap(Scripts.Measure, "T1") }, { "clear", Scripts.Wrap(Scripts.Clear, "T2") },
                    { "dump", Scripts.Wrap(Scripts.Dump, "T3") }, { "restore", Scripts.Wrap(Scripts.Restore(Store.Json().Serialize(tricky)), "T4") },
                }));
                return 0;
            }

            // WebView2 の DLL は exe に埋め込んである。必要になったときにここから読み込む。
            AppDomain.CurrentDomain.AssemblyResolve += ResolveEmbedded;
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool first;
            using (var mutex = new Mutex(true, "HtmlHome.SingleInstance", out first))
            {
                if (!first)
                {
                    MessageBox.Show("HTMLホームはすでに起動しています。", "HTMLホーム", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }
                try
                {
                    string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HtmlHome");
                    Directory.CreateDirectory(root);
                    var store = new Store(root);
                    ExtractLauncher(store.LauncherDir);
                    Shortcut.Ensure(root);
                    RunUi(store);
                }
                catch (Exception ex)
                {
                    MessageBox.Show("起動できませんでした。\n\n" + ex, "HTMLホーム", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
            }
            return 0;
        }

        // WebView2 の型に触るのはこのメソッド以降だけ (AssemblyResolve の登録後に JIT されるように分けている)
        [MethodImpl(MethodImplOptions.NoInlining)]
        static void RunUi(Store store) { Application.Run(new MainForm(store)); }

        static readonly Dictionary<string, Assembly> loaded = new Dictionary<string, Assembly>();
        static Assembly ResolveEmbedded(object sender, ResolveEventArgs e)
        {
            string name = new AssemblyName(e.Name).Name;
            lock (loaded)
            {
                Assembly asm;
                if (loaded.TryGetValue(name, out asm)) return asm;
                byte[] bytes = ReadResource("lib." + name + ".dll");
                if (bytes == null) return null;
                asm = Assembly.Load(bytes);
                loaded[name] = asm;
                return asm;
            }
        }

        static byte[] ReadResource(string name)
        {
            using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (s == null) return null;
                using (var ms = new MemoryStream()) { s.CopyTo(ms); return ms.ToArray(); }
            }
        }

        static void WriteIfChanged(string path, byte[] bytes)
        {
            try
            {
                if (File.Exists(path) && new FileInfo(path).Length == bytes.Length)
                {
                    byte[] cur = File.ReadAllBytes(path);
                    bool same = true;
                    for (int i = 0; i < cur.Length && same; i++) same = cur[i] == bytes[i];
                    if (same) return;
                }
            }
            catch { }
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.WriteAllBytes(path, bytes);
        }

        static void ExtractLauncher(string dir)
        {
            WriteIfChanged(Path.Combine(dir, "index.html"), ReadResource("launcher.index.html"));
        }

        /// <summary>CPU に合った WebView2Loader.dll を取り出し、置いたフォルダーを返す。</summary>
        public static string ExtractLoader(string root)
        {
            string arch = IntPtr.Size == 4 ? "x86"
                : string.Equals(Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE"), "ARM64", StringComparison.OrdinalIgnoreCase) ? "arm64" : "x64";
            string dir = Path.Combine(root, "native", arch);
            WriteIfChanged(Path.Combine(dir, "WebView2Loader.dll"), ReadResource("lib.WebView2Loader." + arch + ".dll"));
            return dir;
        }
    }

    /// <summary>
    /// デスクトップに「HTMLホーム」のショートカットを置く。
    /// 初回起動時に 1 回だけ作る (ユーザーが消したら作り直さない)。
    /// 既にある場合、exe を移動していたらリンク先を今の場所に直す。
    /// </summary>
    static class Shortcut
    {
        const string Name = "HTMLホーム.lnk";

        public static void Ensure(string root)
        {
            try
            {
                string exe = Application.ExecutablePath;
                string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                if (string.IsNullOrEmpty(desktop)) return;
                string lnk = Path.Combine(desktop, Name);
                string flag = Path.Combine(root, "shortcut.done");
                bool exists = File.Exists(lnk);
                if (!exists && File.Exists(flag)) return;      // 一度作って消された → そのまま
                if (exists && string.Equals(ReadTarget(lnk), exe, StringComparison.OrdinalIgnoreCase)) return;
                Write(lnk, exe);
                File.WriteAllText(flag, DateTime.UtcNow.ToString("o"));
            }
            catch { /* ショートカットが作れなくても本体は動かす */ }
        }

        static object Shell()
        {
            Type t = Type.GetTypeFromProgID("WScript.Shell");
            return t == null ? null : Activator.CreateInstance(t);
        }
        static object Call(object o, string m, params object[] a) { return o.GetType().InvokeMember(m, BindingFlags.InvokeMethod, null, o, a); }
        static object Get(object o, string p) { return o.GetType().InvokeMember(p, BindingFlags.GetProperty, null, o, null); }
        static void Set(object o, string p, object v) { o.GetType().InvokeMember(p, BindingFlags.SetProperty, null, o, new[] { v }); }

        static string ReadTarget(string lnk)
        {
            object sh = Shell(); if (sh == null) return null;
            try { return Convert.ToString(Get(Call(sh, "CreateShortcut", lnk), "TargetPath")); }
            finally { Marshal.FinalReleaseComObject(sh); }
        }

        static void Write(string lnk, string exe)
        {
            object sh = Shell(); if (sh == null) return;
            try
            {
                object sc = Call(sh, "CreateShortcut", lnk);
                Set(sc, "TargetPath", exe);
                Set(sc, "WorkingDirectory", Path.GetDirectoryName(exe));
                Set(sc, "IconLocation", exe + ",0");
                Set(sc, "Description", "HTMLホームを起動");
                Call(sc, "Save");
                Marshal.FinalReleaseComObject(sc);
            }
            finally { Marshal.FinalReleaseComObject(sh); }
        }
    }

    /// <summary>
    /// 画面なしで動くデータ処理の自己テスト (HtmlHome.exe --selftest)。
    /// ビルド時に Mono 上で実行して、保存・削除・バックアップの処理を確かめる。
    /// </summary>
    static class SelfTest
    {
        static int fail;
        static void Ok(bool c, string m) { Console.WriteLine((c ? "PASS " : "FAIL ") + m); if (!c) fail++; }
        static bool Throws(Action a) { try { a(); return false; } catch (InvalidOperationException) { return true; } }

        public static int Run()
        {
            try { Console.OutputEncoding = new System.Text.UTF8Encoding(false); } catch { }
            string root = Path.Combine(Path.GetTempPath(), "htmlhome-selftest-" + Guid.NewGuid().ToString("N"));
            try
            {
                var s = new Store(root);
                Ok(s.Apps.Count == 0, "最初はユーザーアプリ 0 個");

                var assets = new List<KeyValuePair<string, byte[]>>
                {
                    new KeyValuePair<string, byte[]>("logo.png", new byte[] { 1, 2, 3, 4, 5 }),
                    new KeyValuePair<string, byte[]>("..\\..\\evil.txt", new byte[] { 9 }),
                    new KeyValuePair<string, byte[]>("../up.txt", new byte[] { 9 }),
                    new KeyValuePair<string, byte[]>("index.html", new byte[] { 9 }),
                    new KeyValuePair<string, byte[]>("__blank.html", new byte[] { 9 }),
                };
                string html = "<!DOCTYPE html><title>メモ</title><p>こんにちは</p>";
                var a = s.Install(" メモ帳 ", html, "data:image/png;base64,AAAA", assets);
                byte[] htmlBytes = new System.Text.UTF8Encoding(false).GetBytes(html);
                Ok(a.name == "メモ帳", "名前の前後の空白を取る");
                Ok(a.sha256 == Store.Sha256Hex(htmlBytes) && a.sha256.Length == 64, "SHA-256 は index.html のハッシュ");
                Ok(File.Exists(Path.Combine(s.AppDir(a.id), "index.html")) && File.Exists(Path.Combine(s.AppDir(a.id), "logo.png")), "HTML と同梱ファイルを保存");
                Ok(File.Exists(Path.Combine(s.AppDir(a.id), "evil.txt")) && File.Exists(Path.Combine(s.AppDir(a.id), "up.txt"))
                    && !File.Exists(Path.Combine(root, "evil.txt")) && !File.Exists(Path.Combine(s.AppsDir, "up.txt")), "同梱ファイルはアプリのフォルダーの外へ出ない");
                Ok(File.ReadAllText(Path.Combine(s.AppDir(a.id), "index.html")) == html, "index.html は同梱ファイルで上書きされない");
                Ok(a.size == htmlBytes.Length + 5 + 1 + 1, "アプリ本体サイズ = HTML + 同梱ファイル (空ページは数えない)");

                Ok(Throws(() => s.Install("メモ帳", html, "", null)), "同じ名前は追加できない");
                Ok(Throws(() => s.Install("メモ帳  ", html, "", null)), "空白違いの同じ名前も不可");
                foreach (string b in new[] { "exit", "EXIT", "Setting", "new app", "Delete" })
                    Ok(Throws(() => s.Install(b, html, "", null)), "標準アプリ名は使えない: " + b);
                Ok(Throws(() => s.Install("", html, "", null)) && Throws(() => s.Install("x", "", "", null)), "空の名前・空の HTML は不可");

                foreach (string b in new[] { "builtin:new", "builtin:delete", "builtin:setting", "builtin:exit" })
                    Ok(Throws(() => s.Uninstall(b)), "標準アプリは消せない: " + b);
                Ok(Throws(() => s.Uninstall("../apps")) && Throws(() => s.Uninstall("nope")), "不正な id は拒否");
                Ok(Directory.Exists(s.AppsDir) && s.Apps.Count == 1, "拒否のあと何も消えていない");

                var b2 = s.Install("ゲーム", "<p>g</p>", "", null);
                s.AddTraffic(a.id, 1500); s.AddTraffic(a.id, -5); s.SetDataSize(a.id, 2048); s.Save();
                s.UiJson = "{\"items\":[]}";
                var s2 = new Store(root);
                Ok(s2.Apps.Count == 2 && s2.Find(a.id).traffic == 1500 && s2.Find(a.id).dataSize == 2048 && s2.UiJson == "{\"items\":[]}", "再起動しても一覧・使用量・並びが残る");

                string bak = Path.Combine(root, "t.hhbak");
                s2.ExportBackup(bak, new Dictionary<string, string> { { a.id, "{\"score\":\"42\"}" } });
                Ok(Store.LooksLikeBackup(bak) && !Store.LooksLikeBackup(Path.Combine(s.AppDir(a.id), "index.html")), "バックアップかどうかを見分ける");
                s2.Uninstall(b2.id);
                Ok(s2.Apps.Count == 1 && !Directory.Exists(s2.AppDir(b2.id)), "アンインストールでフォルダーも消える");
                s2.Install("あとから入れた", "<p>x</p>", "", null);
                var save = s2.ImportBackup(bak);
                Ok(s2.Apps.Count == 2 && s2.Find(a.id) != null && s2.Find(b2.id) != null, "バックアップ読み込みで元の 2 個に戻る");
                Ok(save.Count == 1 && save[a.id] == "{\"score\":\"42\"}", "セーブデータが戻る");
                Ok(File.ReadAllText(Path.Combine(s2.AppDir(a.id), "index.html")) == html && File.Exists(Path.Combine(s2.AppDir(a.id), "logo.png")), "アプリの中身が戻る");
                Ok(s2.Find(a.id).traffic == 1500 && s2.Find(a.id).sha256 == a.sha256, "使用量とハッシュが戻る");
                Ok(Throws(() => s2.ImportBackup(Path.Combine(s2.AppDir(a.id), "index.html"))), "バックアップでないファイルは分かるエラーで断る");
                Ok(s2.Apps.Count == 2 && File.Exists(Path.Combine(s2.AppDir(a.id), "index.html")), "読み込みに失敗しても今のアプリは無事");

                // 悪意のある zip: フォルダーの外へ書こうとする名前
                string evil = Path.Combine(root, "evil.hhbak");
                using (var zip = System.IO.Compression.ZipFile.Open(evil, System.IO.Compression.ZipArchiveMode.Create))
                {
                    Action<string, string> add = (n, t) => { using (var w = new StreamWriter(zip.CreateEntry(n).Open())) w.Write(t); };
                    add("apps.json", "[{\"id\":\"aaaa000001\",\"name\":\"ok\"},{\"id\":\"../../x\",\"name\":\"bad\"},{\"id\":\"aaaa000002\",\"name\":\"exit\"}]");
                    add("apps/aaaa000001/index.html", "<p>ok</p>");
                    add("apps/aaaa000001/../../../escape.txt", "x");
                    add("apps/aaaa000001/sub/deep.txt", "x");
                    add("apps/aaaa000002/index.html", "<p>fake builtin</p>");
                }
                s2.ImportBackup(evil);
                Ok(s2.Apps.Count == 1 && s2.Apps[0].id == "aaaa000001", "不正な id と標準アプリ名のアプリは読み込まない");
                Ok(!File.Exists(Path.Combine(root, "escape.txt")) && !File.Exists(Path.Combine(Path.GetDirectoryName(root), "escape.txt"))
                    && Directory.GetFiles(root, "escape.txt", SearchOption.AllDirectories).Length == 0, "zip の中の ../ は無視される");

                Ok(Store.SafeAssetName("a/b/c.png") == "c.png" && Store.SafeAssetName("..") == null && Store.SafeAssetName("a:b") == null, "ファイル名の検査");
            }
            catch (Exception ex) { Console.WriteLine("FAIL 例外: " + ex); fail++; }
            finally { try { Directory.Delete(root, true); } catch { } }
            Console.WriteLine(fail == 0 ? "\nすべて成功" : "\n" + fail + " 件失敗");
            return fail == 0 ? 0 : 1;
        }
    }
}
