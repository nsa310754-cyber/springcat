using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Threading;

namespace ScreenRec
{
    /// <summary>
    /// 一定の間隔で画面を取り込み、JPEG にして AVI に書き続ける係。
    /// 取り込み方法 (capture) は外から渡すので、Windows 以外でも画像を渡してテストできる。
    /// 取り込みが遅れたときは前のフレームを繰り返して書き、動画の長さが実際の時間とずれないようにする。
    /// </summary>
    public sealed class Recorder
    {
        readonly Func<Bitmap> capture;
        readonly int fps, quality;
        readonly string folder, baseName;
        readonly Size size;
        Thread thread;
        volatile bool stop, paused;
        readonly Stopwatch clock = new Stopwatch();
        long pausedTicks, pauseStartedTicks;
        readonly object gate = new object();

        public long SizeLimit = AviWriter.SizeLimit;
        public readonly List<string> Files = new List<string>();
        public long Bytes { get; private set; }
        public int Frames { get; private set; }
        public int Captured { get; private set; }     // 実際に取り込めた枚数 (残りは繰り返し)
        public Exception Error { get; private set; }
        public bool Running { get { return thread != null && thread.IsAlive; } }
        public bool Paused { get { return paused; } }

        public Recorder(Func<Bitmap> capture, Size size, int fps, int quality, string folder, string baseName)
        {
            if (size.Width < 16 || size.Height < 16) throw new ArgumentException("録画する範囲が小さすぎます");
            this.capture = capture; this.size = size; this.fps = fps; this.quality = quality;
            this.folder = folder; this.baseName = baseName;
        }

        /// <summary>録画している時間 (一時停止中を除く)</summary>
        public TimeSpan Elapsed
        {
            get
            {
                lock (gate)
                {
                    long t = clock.ElapsedTicks - pausedTicks - (paused ? clock.ElapsedTicks - pauseStartedTicks : 0);
                    return TimeSpan.FromSeconds(Math.Max(0, t) / (double)Stopwatch.Frequency);
                }
            }
        }

        public void Start()
        {
            Directory.CreateDirectory(folder);
            stop = false;
            thread = new Thread(Loop) { IsBackground = true, Name = "recorder", Priority = ThreadPriority.AboveNormal };
            clock.Restart();
            thread.Start();
        }

        public void Pause(bool on)
        {
            lock (gate)
            {
                if (on == paused) return;
                if (on) pauseStartedTicks = clock.ElapsedTicks;
                else pausedTicks += clock.ElapsedTicks - pauseStartedTicks;
                paused = on;
            }
        }

        /// <summary>止めて、ファイルを書き終えるまで待つ。</summary>
        public void Stop()
        {
            stop = true;
            if (thread != null) thread.Join();
        }

        string NextPath()
        {
            string name = Files.Count == 0 ? baseName + ".avi" : baseName + "_" + (Files.Count + 1) + ".avi";
            string p = Path.Combine(folder, name);
            Files.Add(p);
            return p;
        }

        static readonly ImageCodecInfo Jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);

        public static byte[] EncodeJpeg(Bitmap bmp, int quality)
        {
            using (var ms = new MemoryStream())
            using (var ps = new EncoderParameters(1))
            {
                ps.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)quality);
                bmp.Save(ms, Jpeg, ps);
                return ms.ToArray();
            }
        }

        void Loop()
        {
            AviWriter avi = null;
            try
            {
                avi = new AviWriter(NextPath(), size.Width, size.Height, fps);
                long prevBytes = 0;
                while (!stop)
                {
                    if (paused) { Thread.Sleep(20); continue; }
                    double sec = Elapsed.TotalSeconds;
                    int due = (int)(sec * fps) + 1;          // 今までに書いてあるべき枚数
                    if (Frames >= due)
                    {
                        double next = (double)due / fps - sec;
                        Thread.Sleep(Math.Max(1, (int)(next * 1000)));
                        continue;
                    }
                    byte[] jpg;
                    using (var bmp = capture())
                        jpg = EncodeJpeg(bmp, quality);
                    if (avi.Length + jpg.Length + 64 > SizeLimit && avi.Frames > 0)
                    {
                        prevBytes += avi.Length; avi.Close();
                        avi = new AviWriter(NextPath(), size.Width, size.Height, fps);
                    }
                    avi.WriteFrame(jpg); Frames++; Captured++;
                    // 取り込みに時間がかかって遅れた分は、同じ絵を繰り返して埋める
                    int dueNow = (int)(Elapsed.TotalSeconds * fps) + 1;
                    while (Frames < dueNow - 1 && !paused && !stop)
                    {
                        if (avi.Length + jpg.Length + 64 > SizeLimit) break;
                        avi.RepeatLast(); Frames++;
                    }
                    Bytes = prevBytes + avi.Length;
                }
            }
            catch (Exception e) { Error = e; }
            finally
            {
                try { if (avi != null) avi.Close(); } catch (Exception e) { if (Error == null) Error = e; }
                clock.Stop();
            }
        }
    }
}
