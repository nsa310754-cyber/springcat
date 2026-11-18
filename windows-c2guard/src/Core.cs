using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Web.Script.Serialization;

namespace C2Guard
{
    /// <summary>C2 リストの 1 件</summary>
    public class Indicator
    {
        public string Ip;
        public string Source;   // どのリストに載っていたか
        public string Label;    // 例: "Possible Cobaltstrike C2 IP"
    }

    /// <summary>今ある通信 1 本 (OS から取ってきたもの)</summary>
    public class Conn
    {
        public string LocalIp, RemoteIp;
        public int LocalPort, RemotePort, Pid;
        public string State;
        public string ProcessName = "", ProcessPath = "";
    }

    /// <summary>C2 リストに当たった通信</summary>
    public class Hit
    {
        public Conn Conn;
        public Indicator Ind;
    }

    /// <summary>
    /// C2 の IP リストの取得・保存・照合。WinForms や Windows API には触らないので Mono で単体テストできる。
    /// </summary>
    public class Feeds
    {
        public class Source { public string Name, Url; }

        // 無料で API キー不要の公開リスト
        public static readonly Source[] Sources =
        {
            new Source { Name = "Feodo Tracker (abuse.ch)", Url = "https://feodotracker.abuse.ch/downloads/ipblocklist.txt" },
            new Source { Name = "Feodo Tracker 推奨 (abuse.ch)", Url = "https://feodotracker.abuse.ch/downloads/ipblocklist_recommended.txt" },
            new Source { Name = "C2IntelFeeds 30日", Url = "https://raw.githubusercontent.com/drb-ra/C2IntelFeeds/master/feeds/IPC2s-30day.csv" },
            new Source { Name = "C2IntelFeeds", Url = "https://raw.githubusercontent.com/drb-ra/C2IntelFeeds/master/feeds/IPC2s.csv" },
        };

        readonly string dir;
        public Dictionary<string, Indicator> Map = new Dictionary<string, Indicator>();
        public DateTime LastUpdate = DateTime.MinValue;
        public List<string> Errors = new List<string>();

        public Feeds(string dir) { this.dir = Path.Combine(dir, "feeds"); Directory.CreateDirectory(this.dir); }

        public string CustomPath { get { return Path.Combine(Path.GetDirectoryName(dir), "custom.txt"); } }

        static string FileName(Source s)
        {
            var sb = new StringBuilder();
            foreach (char c in s.Name) sb.Append(char.IsLetterOrDigit(c) && c < 128 ? c : '_');
            return sb.ToString() + ".txt";
        }

        /// <summary>1 行ずつ読み、先頭の項目が IPv4 なら載せる。# で始まる行と空行は飛ばす。</summary>
        public static List<Indicator> Parse(string text, string source)
        {
            var list = new List<Indicator>();
            if (text == null) return list;
            foreach (string raw in text.Split('\n'))
            {
                string line = raw.Trim();
                if (line.Length == 0 || line[0] == '#' || line[0] == ';') continue;
                string[] parts = line.Split(new[] { ',', '\t', ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length == 0) continue;
                string ip = NormalizeIp(parts[0]);
                if (ip == null) continue;
                string label = parts.Length > 1 ? string.Join(" ", parts.Skip(1).Where(p => !IsNumber(p))).Trim() : "";
                list.Add(new Indicator { Ip = ip, Source = source, Label = label.Length > 0 ? label : "C2 サーバー" });
            }
            return list;
        }

        static bool IsNumber(string s) { int n; return int.TryParse(s, out n); }

        /// <summary>正しい IPv4 だけを "a.b.c.d" の形で返す。それ以外は null。</summary>
        public static string NormalizeIp(string s)
        {
            if (string.IsNullOrEmpty(s)) return null;
            s = s.Trim().Trim('"');
            string[] p = s.Split('.');
            if (p.Length != 4) return null;
            var o = new int[4];
            for (int i = 0; i < 4; i++)
            {
                if (p[i].Length == 0 || p[i].Length > 3) return null;
                foreach (char c in p[i]) if (c < '0' || c > '9') return null;
                o[i] = int.Parse(p[i]);
                if (o[i] > 255) return null;
            }
            return o[0] + "." + o[1] + "." + o[2] + "." + o[3];
        }

        /// <summary>LAN・ループバックなど、外のサーバーではないアドレスか。</summary>
        public static bool IsPrivate(string ip)
        {
            string n = NormalizeIp(ip);
            if (n == null) return true;
            var o = n.Split('.').Select(int.Parse).ToArray();
            return o[0] == 0 || o[0] == 10 || o[0] == 127 || (o[0] == 169 && o[1] == 254) ||
                   (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168) ||
                   (o[0] == 100 && o[1] >= 64 && o[1] <= 127) || o[0] >= 224;
        }

        public void Add(IEnumerable<Indicator> items)
        {
            foreach (var i in items)
            {
                Indicator cur;
                if (Map.TryGetValue(i.Ip, out cur))
                {
                    if (!cur.Source.Contains(i.Source)) cur.Source += " / " + i.Source;
                }
                else Map[i.Ip] = new Indicator { Ip = i.Ip, Source = i.Source, Label = i.Label };
            }
        }

        /// <summary>保存してあるリスト (と自分用リスト) を読み込む。ネットにはつながない。</summary>
        public void LoadCached()
        {
            Map = new Dictionary<string, Indicator>();
            LastUpdate = DateTime.MinValue;
            foreach (var s in Sources)
            {
                string p = Path.Combine(dir, FileName(s));
                if (!File.Exists(p)) continue;
                Add(Parse(File.ReadAllText(p, Encoding.UTF8), s.Name));
                var t = File.GetLastWriteTimeUtc(p);
                if (t > LastUpdate) LastUpdate = t;
            }
            if (File.Exists(CustomPath)) Add(Parse(File.ReadAllText(CustomPath, Encoding.UTF8), "自分用リスト"));
        }

        /// <summary>ネットから最新のリストを取ってくる。取れなかったリストは前回の保存分を使う。</summary>
        public void Download(Func<string, string> fetch)
        {
            Errors.Clear();
            foreach (var s in Sources)
            {
                try
                {
                    string text = fetch(s.Url);
                    if (Parse(text, s.Name).Count == 0 && !text.Contains("#")) throw new Exception("中身が空です");
                    File.WriteAllText(Path.Combine(dir, FileName(s)), text, new UTF8Encoding(false));
                }
                catch (Exception e) { Errors.Add(s.Name + ": " + e.Message); }
            }
            LoadCached();
        }

        public static string HttpFetch(string url)
        {
            ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072;   // TLS 1.2
            var r = (HttpWebRequest)WebRequest.Create(url);
            r.UserAgent = "C2Guard/1.0 (Windows)";
            r.Timeout = 20000; r.ReadWriteTimeout = 20000;
            r.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            using (var resp = r.GetResponse())
            using (var sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                return sr.ReadToEnd();
        }

        /// <summary>通信の一覧から、C2 リストに載っている相手への通信を探す。</summary>
        public List<Hit> Match(IEnumerable<Conn> conns)
        {
            var hits = new List<Hit>();
            foreach (var c in conns)
            {
                Indicator ind;
                if (c.RemoteIp != null && Map.TryGetValue(c.RemoteIp, out ind)) hits.Add(new Hit { Conn = c, Ind = ind });
            }
            return hits;
        }
    }

    /// <summary>ブロックした IP・放置にした IP・ログを保存する。</summary>
    public class State
    {
        public class Entry { public string Ip, Label, Process, Time; }

        public List<Entry> Blocked = new List<Entry>();
        public List<Entry> Ignored = new List<Entry>();
        public List<string> Log = new List<string>();
        public bool AutoWatch = true;

        [ScriptIgnore] public string PathOnDisk;

        public static State Load(string dir)
        {
            string p = System.IO.Path.Combine(dir, "state.json");
            State s = null;
            try { if (File.Exists(p)) s = new JavaScriptSerializer().Deserialize<State>(File.ReadAllText(p, Encoding.UTF8)); } catch { }
            s = s ?? new State();
            s.Blocked = s.Blocked ?? new List<Entry>();
            s.Ignored = s.Ignored ?? new List<Entry>();
            s.Log = s.Log ?? new List<string>();
            s.Blocked.RemoveAll(e => e == null || Feeds.NormalizeIp(e.Ip) == null);
            s.Ignored.RemoveAll(e => e == null || Feeds.NormalizeIp(e.Ip) == null);
            s.PathOnDisk = p;
            return s;
        }

        public void Save()
        {
            if (Log.Count > 500) Log.RemoveRange(0, Log.Count - 500);
            string tmp = PathOnDisk + ".tmp";
            File.WriteAllText(tmp, new JavaScriptSerializer().Serialize(this), new UTF8Encoding(false));
            if (File.Exists(PathOnDisk)) File.Delete(PathOnDisk);
            File.Move(tmp, PathOnDisk);
        }

        public bool IsBlocked(string ip) { return Blocked.Any(e => e.Ip == ip); }
        public bool IsIgnored(string ip) { return Ignored.Any(e => e.Ip == ip); }

        public void AddLog(string msg) { Log.Add(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + msg); }
    }

    /// <summary>Windows ファイアウォール (netsh) に渡す引数を作る。IP は検査済みのものしか通さない。</summary>
    public static class FirewallArgs
    {
        public const string Prefix = "C2Guard-Block-";

        public static string RuleName(string ip)
        {
            string n = Feeds.NormalizeIp(ip);
            if (n == null) throw new ArgumentException("IP アドレスが正しくありません: " + ip);
            return Prefix + n;
        }

        /// <summary>送信と受信の両方を止める 2 つのコマンドの引数。</summary>
        public static string[] Add(string ip)
        {
            string name = RuleName(ip), n = Feeds.NormalizeIp(ip);
            return new[]
            {
                "advfirewall firewall add rule name=\"" + name + "\" dir=out action=block remoteip=" + n + " enable=yes profile=any",
                "advfirewall firewall add rule name=\"" + name + "\" dir=in action=block remoteip=" + n + " enable=yes profile=any",
            };
        }

        public static string Delete(string ip)
        {
            return "advfirewall firewall delete rule name=\"" + RuleName(ip) + "\"";
        }
    }

    /// <summary>
    /// 自己テスト (C2Guard.exe --selftest)。ビルド時に Mono で実行する。
    /// </summary>
    public static class SelfTest
    {
        static int fail;
        static void Ok(bool c, string m) { Console.WriteLine((c ? "PASS " : "FAIL ") + m); if (!c) fail++; }

        public static int Run()
        {
            try { Console.OutputEncoding = new UTF8Encoding(false); } catch { }
            string root = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "c2guard-test-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            try
            {
                // リストの読み方
                string feodo = "#####\n# DstIP\n162.243.103.246\n178.62.3.223\n\n# END 2 entries\n";
                string intel = "#ip,ioc\n1.117.74.144,Possible Cobaltstrike C2 IP\n1.12.253.219,Possible Cobaltstrike C2 IP\n";
                string ipport = "#ip,port,ioc\n5.6.7.8,443,Possible Sliver C2\n";
                var a = Feeds.Parse(feodo, "F");
                Ok(a.Count == 2 && a[0].Ip == "162.243.103.246" && a[0].Label == "C2 サーバー", "Feodo 形式を読める");
                var b = Feeds.Parse(intel, "I");
                Ok(b.Count == 2 && b[0].Label == "Possible Cobaltstrike C2 IP", "C2IntelFeeds 形式を読める (説明付き)");
                var c = Feeds.Parse(ipport, "P");
                Ok(c.Count == 1 && c[0].Label == "Possible Sliver C2", "IP,ポート,説明 の形式でポート番号を説明に入れない");
                Ok(Feeds.Parse("<html><body>410 Gone</body></html>\r\n", "x").Count == 0, "HTML のエラーページは 0 件");
                Ok(Feeds.Parse("1.2.3.4\r\n5.6.7.8\r\n", "x").Count == 2, "CRLF 改行も読める");

                // IP の検査
                foreach (string bad in new[] { "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.4\" & calc", "a.b.c.d", "1.2.3.-4", "", "1..2.3", "1.2.3.4/24" })
                    Ok(Feeds.NormalizeIp(bad) == null, "不正な IP を弾く: " + bad);
                Ok(Feeds.NormalizeIp("010.001.002.003") == "10.1.2.3", "ゼロ埋めの IP を正規化");
                Ok(Feeds.IsPrivate("192.168.1.5") && Feeds.IsPrivate("10.0.0.1") && Feeds.IsPrivate("127.0.0.1") && Feeds.IsPrivate("172.20.1.1"), "LAN のアドレスを判定");
                Ok(!Feeds.IsPrivate("8.8.8.8") && !Feeds.IsPrivate("172.32.0.1"), "外のアドレスは LAN 扱いしない");

                // 照合
                var f = new Feeds(root);
                f.Add(a); f.Add(b); f.Add(Feeds.Parse("1.117.74.144\n", "F2"));
                Ok(f.Map.Count == 4 && f.Map["1.117.74.144"].Source == "I / F2", "同じ IP は 1 件にまとめて出どころを足す");
                var conns = new List<Conn>
                {
                    new Conn { RemoteIp = "8.8.8.8", RemotePort = 443, Pid = 10, ProcessName = "chrome.exe" },
                    new Conn { RemoteIp = "178.62.3.223", RemotePort = 8080, Pid = 20, ProcessName = "evil.exe" },
                    new Conn { RemoteIp = "1.117.74.144", RemotePort = 443, Pid = 30, ProcessName = "rundll32.exe" },
                };
                var hits = f.Match(conns);
                Ok(hits.Count == 2 && hits[0].Conn.Pid == 20 && hits[1].Ind.Label.Contains("Cobaltstrike"), "リストに載った相手への通信だけ見つける");

                // ダウンロード (取れたもの・取れないものが混ざる)
                f.Download(url => { if (url.Contains("recommended")) return feodo; if (url.Contains("30day")) return intel; throw new Exception("接続できません"); });
                Ok(f.Map.Count == 4 && f.Errors.Count == 2, "取れたリストだけ使い、失敗は記録する");
                f.Download(url => { throw new Exception("オフライン"); });
                Ok(f.Map.Count == 4, "オフラインでも前回のリストで動く");
                File.WriteAllText(f.CustomPath, "# 自分用\n203.0.113.9 テスト用\n");
                f.LoadCached();
                Ok(f.Map.ContainsKey("203.0.113.9") && f.Map["203.0.113.9"].Source == "自分用リスト", "自分用リストも読む");

                // 状態の保存
                var s = State.Load(root);
                s.Blocked.Add(new State.Entry { Ip = "178.62.3.223", Label = "x", Process = "evil.exe", Time = "t" });
                s.Ignored.Add(new State.Entry { Ip = "1.117.74.144", Label = "y", Process = "z", Time = "t" });
                s.Ignored.Add(new State.Entry { Ip = "not-an-ip" });
                for (int i = 0; i < 600; i++) s.AddLog("log " + i);
                s.Save();
                var s2 = State.Load(root);
                Ok(s2.IsBlocked("178.62.3.223") && s2.IsIgnored("1.117.74.144") && !s2.IsIgnored("8.8.8.8"), "ブロック・放置リストが残る");
                Ok(s2.Ignored.Count == 1, "壊れた IP の項目は読み込まない");
                Ok(s2.Log.Count == 500 && s2.Log[499].EndsWith("log 599"), "ログは最新 500 件");

                // ファイアウォールの引数
                var add = FirewallArgs.Add("178.62.3.223");
                Ok(add.Length == 2 && add[0].Contains("dir=out") && add[1].Contains("dir=in") && add[0].Contains("remoteip=178.62.3.223 ")
                   && add[0].Contains("name=\"C2Guard-Block-178.62.3.223\""), "送信・受信の両方を止めるルール");
                Ok(FirewallArgs.Delete("178.62.3.223") == "advfirewall firewall delete rule name=\"C2Guard-Block-178.62.3.223\"", "ルールの削除");
                bool threw = false;
                try { FirewallArgs.Add("1.2.3.4\" action=allow"); } catch (ArgumentException) { threw = true; }
                Ok(threw, "怪しい文字を含む IP ではコマンドを作らない");
            }
            catch (Exception e) { Console.WriteLine("FAIL 例外: " + e); fail++; }
            finally { try { Directory.Delete(root, true); } catch { } }
            Console.WriteLine(fail == 0 ? "\nすべて成功" : "\n" + fail + " 件失敗");
            return fail == 0 ? 0 : 1;
        }
    }
}
