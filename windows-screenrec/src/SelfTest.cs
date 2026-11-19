using System;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading;

namespace ScreenRec
{
    /// <summary>
    /// 自己テスト (ScreenRec.exe --selftest 出力フォルダー)。Mono で実行する。
    /// 画面の代わりに、動く四角と番号を描いた絵を「取り込み」として渡し、実際に AVI を作る。
    /// できた AVI は build.sh が ffprobe / ffmpeg で再生できるか確かめる。
    /// </summary>
    static class SelfTest
    {
        static int fail;
        static void Ok(bool c, string m) { Console.WriteLine((c ? "PASS " : "FAIL ") + m); if (!c) fail++; }

        static Bitmap Fake(int n, int w, int h, int delayMs)
        {
            if (delayMs > 0) Thread.Sleep(delayMs);
            var b = new Bitmap(w, h);
            using (var g = Graphics.FromImage(b))
            {
                g.Clear(Color.FromArgb(30, 40, 60));
                g.FillRectangle(Brushes.Orange, (n * 12) % (w - 60), h / 3, 60, 60);
                g.DrawString("frame " + n, SystemFonts.DefaultFont, Brushes.White, 10, 10);
            }
            return b;
        }

        /// <summary>AVI を読み直して、フレーム数・サイズ・索引が正しいか確かめる。</summary>
        static void CheckAvi(string path, int expectFrames, int w, int h, string label)
        {
            byte[] d = File.ReadAllBytes(path);
            Func<int, string> cc = o => Encoding.ASCII.GetString(d, o, 4);
            Func<int, int> i32 = o => BitConverter.ToInt32(d, o);
            Ok(cc(0) == "RIFF" && cc(8) == "AVI " && i32(4) == d.Length - 8, label + ": RIFF の大きさが正しい");
            int avih = Encoding.ASCII.GetString(d).IndexOf("avih");
            Ok(i32(avih + 8 + 16) == expectFrames && i32(avih + 8 + 32) == w && i32(avih + 8 + 36) == h,
               label + ": ヘッダーのフレーム数 " + i32(avih + 8 + 16) + " / サイズ " + i32(avih + 8 + 32) + "x" + i32(avih + 8 + 36));
            int movi = Encoding.ASCII.GetString(d).IndexOf("movi");
            int idx = Encoding.ASCII.GetString(d).LastIndexOf("idx1");
            int n = i32(idx + 4) / 16;
            bool allJpeg = n == expectFrames;
            for (int k = 0; k < n && allJpeg; k++)
            {
                int off = i32(idx + 8 + k * 16 + 8), size = i32(idx + 8 + k * 16 + 12);
                int p = movi + off;
                allJpeg = cc(p) == "00dc" && i32(p + 4) == size && d[p + 8] == 0xFF && d[p + 9] == 0xD8;
            }
            Ok(allJpeg, label + ": 索引の全 " + n + " 枚が JPEG を指している");
        }

        public static int Run(string outDir)
        {
            try { Console.OutputEncoding = new UTF8Encoding(false); } catch { }
            Directory.CreateDirectory(outDir);
            foreach (var f in Directory.GetFiles(outDir, "*.avi")) File.Delete(f);
            try
            {
                // 1) 普通に 2 秒、10fps
                int n = 0;
                var r = new Recorder(() => Fake(n++, 320, 240, 0), new Size(320, 240), 10, 70, outDir, "basic");
                r.Start(); Thread.Sleep(2000); r.Stop();
                Ok(r.Error == null, "エラーなく終わる" + (r.Error != null ? ": " + r.Error : ""));
                Ok(r.Files.Count == 1 && r.Frames >= 19 && r.Frames <= 22, "2 秒 × 10fps ≒ 20 枚 (" + r.Frames + ")");
                CheckAvi(r.Files[0], r.Frames, 320, 240, "basic");

                // 2) 取り込みが遅い (1 枚 250ms) → 繰り返しで埋めて長さを保つ
                n = 0;
                var slow = new Recorder(() => Fake(n++, 320, 240, 250), new Size(320, 240), 10, 70, outDir, "slow");
                slow.Start(); Thread.Sleep(2000); slow.Stop();
                Ok(slow.Frames >= 17 && slow.Captured <= 10, "遅い取り込みでも長さが合う (" + slow.Frames + " 枚中 実取り込み " + slow.Captured + ")");
                CheckAvi(slow.Files[0], slow.Frames, 320, 240, "slow");

                // 3) 一時停止中は進まない
                n = 0;
                var p = new Recorder(() => Fake(n++, 320, 240, 0), new Size(320, 240), 10, 70, outDir, "pause");
                p.Start(); Thread.Sleep(1000); p.Pause(true); int atPause = p.Frames; Thread.Sleep(1000);
                int during = p.Frames; p.Pause(false); Thread.Sleep(1000); p.Stop();
                Ok(during - atPause <= 1 && p.Frames >= 18 && p.Frames <= 23 && p.Elapsed.TotalSeconds < 2.3,
                   "一時停止の間は書かない (" + atPause + "→" + during + "、合計 " + p.Frames + " 枚、" + p.Elapsed.TotalSeconds.ToString("0.0") + " 秒)");

                // 4) 大きさの上限で次のファイルへ
                n = 0;
                var s = new Recorder(() => Fake(n++, 320, 240, 0), new Size(320, 240), 10, 90, outDir, "split") { SizeLimit = 16 * 1024 };
                s.Start(); Thread.Sleep(2000); s.Stop();
                Ok(s.Files.Count >= 2 && s.Files.TrueForAll(File.Exists), "上限を超えたら次のファイルに分ける (" + s.Files.Count + " 個)");
                Ok(s.Files.TrueForAll(f => new FileInfo(f).Length <= 16 * 1024 + 16 * 1024), "どのファイルも上限付近に収まる");
                for (int i = 0; i < s.Files.Count; i++) CheckAvi(s.Files[i], CountFrames(s.Files[i]), 320, 240, "split" + (i + 1));

                // 5) 取り込みで例外 → 止まってエラーを報告、ファイルは壊れない
                n = 0;
                var e = new Recorder(() => { if (n++ > 5) throw new InvalidOperationException("取り込み失敗"); return Fake(n, 320, 240, 0); },
                    new Size(320, 240), 10, 70, outDir, "error");
                e.Start(); Thread.Sleep(1500); e.Stop();
                Ok(e.Error is InvalidOperationException, "取り込みの失敗を報告する");
                CheckAvi(e.Files[0], e.Frames, 320, 240, "error");
            }
            catch (Exception ex) { Console.WriteLine("FAIL 例外: " + ex); fail++; }
            Console.WriteLine(fail == 0 ? "\nすべて成功" : "\n" + fail + " 件失敗");
            return fail == 0 ? 0 : 1;
        }

        static int CountFrames(string path)
        {
            byte[] d = File.ReadAllBytes(path);
            int idx = Encoding.ASCII.GetString(d).LastIndexOf("idx1");
            return BitConverter.ToInt32(d, idx + 4) / 16;
        }
    }
}
