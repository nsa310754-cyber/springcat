using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;

namespace HtmlHome
{
    /// <summary>回線速度の測定。Cloudflare の公開スピードテスト用エンドポイントを使う。</summary>
    public static class SpeedTest
    {
        const string Base = "https://speed.cloudflare.com/";
        const string UA = "Mozilla/5.0 (HtmlHome speed test)";

        static HttpWebRequest Req(string url, string method)
        {
            var r = (HttpWebRequest)WebRequest.Create(url);
            r.Method = method; r.UserAgent = UA; r.Timeout = 15000; r.ReadWriteTimeout = 15000;
            r.KeepAlive = true;
            return r;
        }

        public static Dictionary<string, object> Run()
        {
            ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072;   // TLS 1.2
            ServicePointManager.Expect100Continue = false;
            var buf = new byte[64 * 1024];

            // 応答時間: 小さなリクエストを 4 回、最初の 1 回 (接続確立込み) は捨てて最小値
            double ping = double.MaxValue;
            for (int i = 0; i < 4; i++)
            {
                var sw = Stopwatch.StartNew();
                using (var resp = Req(Base + "__down?bytes=0", "GET").GetResponse())
                using (var s = resp.GetResponseStream()) { while (s.Read(buf, 0, buf.Length) > 0) { } }
                sw.Stop();
                if (i > 0) ping = Math.Min(ping, sw.Elapsed.TotalMilliseconds);
            }

            // 下り: 最大 100MB を最長 6 秒読む
            long down = 0; double downSec;
            {
                using (var resp = Req(Base + "__down?bytes=100000000", "GET").GetResponse())
                using (var s = resp.GetResponseStream())
                {
                    var sw = Stopwatch.StartNew();
                    int n;
                    while (sw.Elapsed.TotalSeconds < 6 && (n = s.Read(buf, 0, buf.Length)) > 0) down += n;
                    sw.Stop(); downSec = Math.Max(0.001, sw.Elapsed.TotalSeconds);
                }
            }

            // 上り: 最大 25MB を最長 5 秒送る
            long up = 0; double upSec;
            {
                var r = Req(Base + "__up", "POST");
                r.SendChunked = true; r.AllowWriteStreamBuffering = false;
                r.ContentType = "application/octet-stream";
                new Random().NextBytes(buf);
                var sw = Stopwatch.StartNew();
                using (var s = r.GetRequestStream())
                {
                    while (sw.Elapsed.TotalSeconds < 5 && up < 25L * 1024 * 1024) { s.Write(buf, 0, buf.Length); up += buf.Length; }
                }
                using (var resp = r.GetResponse()) { }
                sw.Stop(); upSec = Math.Max(0.001, sw.Elapsed.TotalSeconds);
            }

            return new Dictionary<string, object>
            {
                { "pingMs", Math.Round(ping, 1) },
                { "downMbps", Math.Round(down * 8 / downSec / 1e6, 2) },
                { "upMbps", Math.Round(up * 8 / upSec / 1e6, 2) },
            };
        }
    }
}
