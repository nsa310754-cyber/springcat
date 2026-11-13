using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace HtmlHome
{
    /// <summary>ユーザーが追加したアプリ 1 個ぶんの情報。apps.json にそのまま保存する。</summary>
    public class AppMeta
    {
        public string id;
        public string name;
        public string icon;       // data: URL
        public string installed;  // ISO 8601 (UTC)
        public string sha256;     // index.html のハッシュ
        public long size;         // アプリ本体 (bytes)
        public long dataSize;     // セーブデータ (bytes)
        public long traffic;      // 通信使用量 (bytes)
    }

    /// <summary>
    /// アプリ本体・一覧・ホーム画面の並びをディスクに保存する係。
    /// WebView2 や WinForms には触らないので、Windows 以外でも単体テストできる。
    /// </summary>
    public class Store
    {
        public const string HostSuffix = ".htmlhome.example";
        public const string BlankPage = "__blank.html";
        // 最初からあるアプリ。ストアには入っておらず、消す手段も用意しない。
        public static readonly string[] BuiltinNames = { "New app", "Delete", "Setting", "exit" };

        public readonly string Root, AppsDir, LauncherDir, WebViewDir;
        readonly string appsJson, uiJson;
        readonly object gate = new object();
        List<AppMeta> apps = new List<AppMeta>();

        public Store(string root)
        {
            Root = root;
            AppsDir = Path.Combine(root, "apps");
            LauncherDir = Path.Combine(root, "launcher");
            WebViewDir = Path.Combine(root, "WebView2");
            appsJson = Path.Combine(root, "apps.json");
            uiJson = Path.Combine(root, "ui.json");
            Directory.CreateDirectory(AppsDir);
            Directory.CreateDirectory(LauncherDir);
            Load();
        }

        public static JavaScriptSerializer Json()
        {
            return new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 64 };
        }

        public static bool IsBuiltinId(string id) { return id != null && id.StartsWith("builtin:", StringComparison.Ordinal); }

        static bool ValidId(string id)
        {
            if (string.IsNullOrEmpty(id) || id.Length > 40) return false;
            foreach (char c in id) if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
            return true;
        }

        void Load()
        {
            lock (gate)
            {
                apps = new List<AppMeta>();
                if (!File.Exists(appsJson)) return;
                try
                {
                    var list = Json().Deserialize<List<AppMeta>>(File.ReadAllText(appsJson, Encoding.UTF8));
                    if (list == null) return;
                    foreach (var a in list)
                        if (a != null && ValidId(a.id) && File.Exists(Path.Combine(AppDir(a.id), "index.html"))) apps.Add(a);
                }
                catch { /* 壊れていたら空から始める */ }
            }
        }

        public void Save()
        {
            lock (gate) { WriteAtomic(appsJson, Json().Serialize(apps)); }
        }

        static void WriteAtomic(string path, string text)
        {
            string tmp = path + ".tmp";
            File.WriteAllText(tmp, text, new UTF8Encoding(false));
            if (File.Exists(path)) File.Delete(path);
            File.Move(tmp, path);
        }

        public string UiJson
        {
            get { try { return File.Exists(uiJson) ? File.ReadAllText(uiJson, Encoding.UTF8) : null; } catch { return null; } }
            set { WriteAtomic(uiJson, value ?? ""); }
        }

        public List<AppMeta> Apps { get { lock (gate) return new List<AppMeta>(apps); } }
        public AppMeta Find(string id) { lock (gate) return apps.FirstOrDefault(a => a.id == id); }
        public string AppDir(string id) { return Path.Combine(AppsDir, id); }
        public static string HostOf(string id) { return id + HostSuffix; }
        public static string OriginOf(string id) { return "https://" + HostOf(id); }

        public bool NameTaken(string name)
        {
            string n = (name ?? "").Trim();
            if (BuiltinNames.Any(b => string.Equals(b, n, StringComparison.OrdinalIgnoreCase))) return true;
            lock (gate) return apps.Any(a => string.Equals((a.name ?? "").Trim(), n, StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>同梱ファイル名として安全か。パス区切りや予約名は受け付けない。</summary>
        public static string SafeAssetName(string name)
        {
            if (string.IsNullOrWhiteSpace(name)) return null;
            string n = name.Replace('\\', '/');
            n = n.Substring(n.LastIndexOf('/') + 1).Trim();
            if (n.Length == 0 || n.Length > 120 || n == "." || n == "..") return null;
            if (n.StartsWith("__", StringComparison.Ordinal)) return null;
            if (string.Equals(n, "index.html", StringComparison.OrdinalIgnoreCase)) return null;
            if (n.IndexOfAny(new[] { '<', '>', ':', '"', '|', '?', '*', '\0' }) >= 0) return null;
            foreach (char c in n) if (c < 32) return null;
            return n;
        }

        public AppMeta Install(string name, string html, string icon, IEnumerable<KeyValuePair<string, byte[]>> assets)
        {
            name = (name ?? "").Trim();
            if (name.Length == 0) throw new InvalidOperationException("アプリ名を入れてください");
            if (name.Length > 60) throw new InvalidOperationException("アプリ名が長すぎます");
            if (string.IsNullOrEmpty(html)) throw new InvalidOperationException("HTML が空です");
            if (NameTaken(name)) throw new InvalidOperationException("同じ名前のアプリがすでにあります");

            string id;
            do { id = "a" + Guid.NewGuid().ToString("N").Substring(0, 10); } while (Directory.Exists(AppDir(id)));
            string dir = AppDir(id);
            Directory.CreateDirectory(dir);
            try
            {
                byte[] bytes = new UTF8Encoding(false).GetBytes(html);
                File.WriteAllBytes(Path.Combine(dir, "index.html"), bytes);
                File.WriteAllText(Path.Combine(dir, BlankPage), "<!doctype html><meta charset=\"utf-8\"><title></title>");
                if (assets != null)
                    foreach (var kv in assets)
                    {
                        string safe = SafeAssetName(kv.Key);
                        if (safe == null || kv.Value == null) continue;
                        File.WriteAllBytes(Path.Combine(dir, safe), kv.Value);
                    }
                var meta = new AppMeta
                {
                    id = id, name = name, icon = icon ?? "", installed = DateTime.UtcNow.ToString("o"),
                    sha256 = Sha256Hex(bytes), size = MeasureDir(dir), dataSize = 0, traffic = 0,
                };
                lock (gate) apps.Add(meta);
                Save();
                return meta;
            }
            catch
            {
                try { Directory.Delete(dir, true); } catch { }
                throw;
            }
        }

        public void Uninstall(string id)
        {
            if (IsBuiltinId(id)) throw new InvalidOperationException("最初からあるアプリは消せません");
            if (!ValidId(id)) throw new InvalidOperationException("アプリが見つかりません");
            lock (gate)
            {
                int i = apps.FindIndex(a => a.id == id);
                if (i < 0) throw new InvalidOperationException("アプリが見つかりません");
                apps.RemoveAt(i);
            }
            try { if (Directory.Exists(AppDir(id))) Directory.Delete(AppDir(id), true); } catch { }
            Save();
        }

        public void AddTraffic(string id, long bytes)
        {
            if (bytes <= 0) return;
            lock (gate) { var a = apps.FirstOrDefault(x => x.id == id); if (a != null) a.traffic += bytes; }
        }
        public void SetDataSize(string id, long bytes)
        {
            lock (gate) { var a = apps.FirstOrDefault(x => x.id == id); if (a != null) a.dataSize = Math.Max(0, bytes); }
        }
        public void ResetTraffic()
        {
            lock (gate) foreach (var a in apps) a.traffic = 0;
            Save();
        }

        public static string Sha256Hex(byte[] data)
        {
            using (var sha = SHA256.Create())
            {
                var sb = new StringBuilder(64);
                foreach (byte b in sha.ComputeHash(data)) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }

        static long MeasureDir(string dir)
        {
            long n = 0;
            foreach (string f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                if (Path.GetFileName(f) != BlankPage) n += new FileInfo(f).Length;
            return n;
        }

        // ---------------- バックアップ ----------------
        // zip の中身: apps.json / ui.json / apps/<id>/<file> / savedata/<id>.json (localStorage の中身)

        public void ExportBackup(string path, IDictionary<string, string> saveData)
        {
            Save();
            if (File.Exists(path)) File.Delete(path);
            using (var zip = ZipFile.Open(path, ZipArchiveMode.Create))
            {
                AddText(zip, "apps.json", Json().Serialize(Apps));
                AddText(zip, "ui.json", UiJson ?? "");
                foreach (var a in Apps)
                {
                    foreach (string f in Directory.GetFiles(AppDir(a.id)))
                        zip.CreateEntryFromFile(f, "apps/" + a.id + "/" + Path.GetFileName(f));
                    string sd;
                    if (saveData != null && saveData.TryGetValue(a.id, out sd) && !string.IsNullOrEmpty(sd))
                        AddText(zip, "savedata/" + a.id + ".json", sd);
                }
            }
        }

        static void AddText(ZipArchive zip, string name, string text)
        {
            var e = zip.CreateEntry(name);
            using (var w = new StreamWriter(e.Open(), new UTF8Encoding(false))) w.Write(text);
        }

        static string ReadText(ZipArchiveEntry e)
        {
            using (var r = new StreamReader(e.Open(), Encoding.UTF8)) return r.ReadToEnd();
        }

        public static bool LooksLikeBackup(string path)
        {
            try
            {
                using (var zip = ZipFile.OpenRead(path))
                {
                    var e = zip.GetEntry("apps.json");
                    return e != null && Json().Deserialize<List<AppMeta>>(ReadText(e)) != null;
                }
            }
            catch { return false; }
        }

        /// <summary>
        /// バックアップを読み込み、今のアプリを全部置き換える。戻り値は id → セーブデータ(JSON)。
        /// 先に一時フォルダーへ全部展開して検証し、成功してから入れ替える。
        /// </summary>
        public Dictionary<string, string> ImportBackup(string path)
        {
            var save = new Dictionary<string, string>();
            string tmp = Path.Combine(Root, "import.tmp");
            if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
            Directory.CreateDirectory(tmp);
            try
            {
                List<AppMeta> list; string ui = null;
                ZipArchive opened;
                try { opened = ZipFile.OpenRead(path); }
                catch (InvalidDataException) { throw new InvalidOperationException("HTMLホームのバックアップではありません"); }
                using (var zip = opened)
                {
                    var ae = zip.GetEntry("apps.json");
                    if (ae == null) throw new InvalidOperationException("HTMLホームのバックアップではありません");
                    list = Json().Deserialize<List<AppMeta>>(ReadText(ae)) ?? new List<AppMeta>();
                    var ue = zip.GetEntry("ui.json");
                    if (ue != null) ui = ReadText(ue);

                    var seenNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    var ok = new List<AppMeta>();
                    foreach (var a in list)
                    {
                        if (a == null || !ValidId(a.id)) continue;
                        string nm = (a.name ?? "").Trim();
                        if (nm.Length == 0 || !seenNames.Add(nm)) continue;
                        if (BuiltinNames.Any(b => string.Equals(b, nm, StringComparison.OrdinalIgnoreCase))) continue;
                        string prefix = "apps/" + a.id + "/";
                        string dir = Path.Combine(tmp, a.id);
                        Directory.CreateDirectory(dir);
                        bool hasIndex = false;
                        foreach (var e in zip.Entries)
                        {
                            if (!e.FullName.StartsWith(prefix, StringComparison.Ordinal)) continue;
                            string fn = e.FullName.Substring(prefix.Length);
                            bool special = fn == "index.html" || fn == BlankPage;
                            // フォルダー外へ書き出そうとする名前 (../ など) は SafeAssetName が落とす
                            if (!special && SafeAssetName(fn) != fn) continue;
                            e.ExtractToFile(Path.Combine(dir, fn), true);
                            if (fn == "index.html") hasIndex = true;
                        }
                        if (!hasIndex) { Directory.Delete(dir, true); continue; }
                        File.WriteAllText(Path.Combine(dir, BlankPage), "<!doctype html><meta charset=\"utf-8\"><title></title>");
                        a.size = MeasureDir(dir);
                        a.sha256 = Sha256Hex(File.ReadAllBytes(Path.Combine(dir, "index.html")));
                        a.dataSize = 0;
                        if (a.traffic < 0) a.traffic = 0;
                        ok.Add(a);
                        var se = zip.GetEntry("savedata/" + a.id + ".json");
                        if (se != null) save[a.id] = ReadText(se);
                    }
                    list = ok;
                }
                // ここまで来たら入れ替える
                lock (gate)
                {
                    if (Directory.Exists(AppsDir)) Directory.Delete(AppsDir, true);
                    Directory.Move(tmp, AppsDir);
                    apps = list;
                }
                Save();
                if (ui != null) UiJson = ui;
                return save;
            }
            finally
            {
                try { if (Directory.Exists(tmp)) Directory.Delete(tmp, true); } catch { }
            }
        }
    }

    /// <summary>
    /// アプリのデータ領域 (アプリと同じ仮想ホストの空ページ) で実行するスクリプト。
    /// どれも async 関数の中身として書き、結果を return する。
    /// </summary>
    public static class Scripts
    {
        public const string Measure =
            "let n=0;try{for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);" +
            "n+=(k.length+(localStorage.getItem(k)||'').length)*2}}catch(e){}" +
            "let u=0;try{const e=await navigator.storage.estimate();u=e.usage||0}catch(e){}" +
            "return {bytes:n+u};";

        public const string Clear =
            "try{localStorage.clear()}catch(e){}try{sessionStorage.clear()}catch(e){}" +
            "try{const dbs=await indexedDB.databases();await Promise.all(dbs.map(d=>new Promise(r=>{" +
            "const q=indexedDB.deleteDatabase(d.name);q.onsuccess=q.onerror=q.onblocked=()=>r()})))}catch(e){}" +
            "try{const ks=await caches.keys();await Promise.all(ks.map(k=>caches.delete(k)))}catch(e){}" +
            "try{const rs=await navigator.serviceWorker.getRegistrations();await Promise.all(rs.map(r=>r.unregister()))}catch(e){}" +
            "return {ok:true};";

        public const string Dump =
            "const o={};for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);o[k]=localStorage.getItem(k)}return o;";

        /// <summary>バックアップから localStorage を戻す。dataJson は JavaScriptSerializer で作った JSON オブジェクト。</summary>
        public static string Restore(string dataJson)
        {
            return "const d=" + dataJson + ";for(const k in d){localStorage.setItem(k,String(d[k]))}return {ok:true};";
        }

        /// <summary>本体を実行し、結果を合言葉 (token) 付きでホストへ送り返す形に包む。</summary>
        public static string Wrap(string body, string token)
        {
            return "(async()=>{let v;try{v=await(async()=>{" + body + "})()}catch(e){v={error:String(e)}}" +
                   "chrome.webview.postMessage(JSON.stringify({__hh:'" + token + "',v:v}))})()";
        }
    }
}
