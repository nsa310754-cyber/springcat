using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace ScreenRec
{
    /// <summary>
    /// MJPEG (JPEG を並べた動画) の AVI ファイルを書く。外部ライブラリなし。
    /// Windows の「メディア プレーヤー」「映画 & テレビ」、VLC などで再生できる。
    /// AVI 1.0 形式なので 1 ファイルの大きさに上限があり、呼び出し側が SizeLimit を超えたら次のファイルに切り替える。
    /// </summary>
    public sealed class AviWriter : IDisposable
    {
        public const long SizeLimit = 1800L * 1024 * 1024;   // 1.8GB で切り替え (2GB 制限の手前)

        readonly FileStream fs;
        readonly BinaryWriter w;
        readonly int width, height, fps;
        readonly List<long> offsets = new List<long>();   // movi の先頭からの位置
        readonly List<int> sizes = new List<int>();
        long riffSizePos, moviSizePos, moviStart, totalFramesPos, lengthPos, maxBytesPos, suggestedPos;
        int maxFrame;
        byte[] last;
        bool closed;

        public int Frames { get { return offsets.Count; } }
        public long Length { get { return fs.Length; } }
        public string Path { get; private set; }

        public AviWriter(string path, int width, int height, int fps)
        {
            if (width <= 0 || height <= 0 || fps <= 0) throw new ArgumentException("サイズかフレームレートが不正です");
            Path = path; this.width = width; this.height = height; this.fps = fps;
            fs = new FileStream(path, FileMode.Create, FileAccess.ReadWrite, FileShare.Read, 1 << 20);
            w = new BinaryWriter(fs);
            WriteHeader();
        }

        void Fourcc(string s) { w.Write(Encoding.ASCII.GetBytes(s)); }

        void WriteHeader()
        {
            Fourcc("RIFF"); riffSizePos = fs.Position; w.Write(0); Fourcc("AVI ");

            Fourcc("LIST"); long hdrlSize = fs.Position; w.Write(0); Fourcc("hdrl");
            // ---- avih (MainAVIHeader) ----
            Fourcc("avih"); w.Write(56);
            w.Write(1000000 / fps);          // dwMicroSecPerFrame
            maxBytesPos = fs.Position; w.Write(0);   // dwMaxBytesPerSec
            w.Write(0);                      // dwPaddingGranularity
            w.Write(0x10);                   // dwFlags = AVIF_HASINDEX
            totalFramesPos = fs.Position; w.Write(0); // dwTotalFrames
            w.Write(0);                      // dwInitialFrames
            w.Write(1);                      // dwStreams
            w.Write(0);                      // dwSuggestedBufferSize
            w.Write(width); w.Write(height);
            w.Write(0); w.Write(0); w.Write(0); w.Write(0);

            Fourcc("LIST"); long strlSize = fs.Position; w.Write(0); Fourcc("strl");
            // ---- strh (AVIStreamHeader) ----
            Fourcc("strh"); w.Write(56);
            Fourcc("vids"); Fourcc("MJPG");
            w.Write(0);                      // dwFlags
            w.Write((short)0); w.Write((short)0); // wPriority, wLanguage
            w.Write(0);                      // dwInitialFrames
            w.Write(1); w.Write(fps);        // dwScale, dwRate → fps
            w.Write(0);                      // dwStart
            lengthPos = fs.Position; w.Write(0); // dwLength (フレーム数)
            suggestedPos = fs.Position; w.Write(0); // dwSuggestedBufferSize
            w.Write(-1);                     // dwQuality
            w.Write(0);                      // dwSampleSize
            w.Write((short)0); w.Write((short)0); w.Write((short)width); w.Write((short)height); // rcFrame
            // ---- strf (BITMAPINFOHEADER) ----
            Fourcc("strf"); w.Write(40);
            w.Write(40); w.Write(width); w.Write(height);
            w.Write((short)1); w.Write((short)24);
            Fourcc("MJPG");
            w.Write(width * height * 3);
            w.Write(0); w.Write(0); w.Write(0); w.Write(0);
            Patch(strlSize, fs.Position - strlSize - 4);
            Patch(hdrlSize, fs.Position - hdrlSize - 4);

            Fourcc("LIST"); moviSizePos = fs.Position; w.Write(0); Fourcc("movi");
            moviStart = fs.Position - 4;     // idx1 のオフセットは "movi" の位置から数える
        }

        void Patch(long pos, long value)
        {
            long cur = fs.Position;
            fs.Position = pos; w.Write((int)value);
            fs.Position = cur;
        }

        /// <summary>JPEG 1 枚を 1 フレームとして書く。</summary>
        public void WriteFrame(byte[] jpeg)
        {
            if (closed) throw new ObjectDisposedException("AviWriter");
            if (jpeg == null || jpeg.Length == 0) throw new ArgumentException("空のフレーム");
            offsets.Add(fs.Position - moviStart);
            sizes.Add(jpeg.Length);
            Fourcc("00dc"); w.Write(jpeg.Length); w.Write(jpeg);
            if ((jpeg.Length & 1) == 1) w.Write((byte)0);
            if (jpeg.Length > maxFrame) maxFrame = jpeg.Length;
            last = jpeg;
        }

        /// <summary>前のフレームをもう一度書く (取り込みが間に合わなかったとき、時間をずらさないため)。</summary>
        public void RepeatLast() { if (last != null) WriteFrame(last); }

        public void Close()
        {
            if (closed) return;
            closed = true;
            long moviEnd = fs.Position;
            Patch(moviSizePos, moviEnd - moviSizePos - 4);
            Fourcc("idx1"); w.Write(offsets.Count * 16);
            for (int i = 0; i < offsets.Count; i++)
            {
                Fourcc("00dc"); w.Write(0x10 /* AVIIF_KEYFRAME */); w.Write((int)offsets[i]); w.Write(sizes[i]);
            }
            Patch(riffSizePos, fs.Position - 8);
            Patch(totalFramesPos, offsets.Count);
            Patch(lengthPos, offsets.Count);
            Patch(suggestedPos, maxFrame + 8);
            Patch(maxBytesPos, (long)maxFrame * fps);
            w.Flush();
            fs.Dispose();
        }

        public void Dispose() { Close(); }
    }
}
