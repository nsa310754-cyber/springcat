using System;
using System.IO;
using System.Text;

namespace BionicRunnerPC
{
    /// <summary>
    /// 実行ファイルのヘッダを読んで種類 (bionic / glibc / 静的 / スクリプト / Windows 用 …) を判定する。
    /// Android 版 Bionic Runner の ElfInfo.java をそのまま移植したもの。
    /// </summary>
    public sealed class ElfInfo
    {
        public enum Kind
        {
            /// <summary>動的リンクで、ローダが Android の /system/bin/linker(64) = bionic 用</summary>
            BionicDynamic,
            /// <summary>静的リンク (ローダ不要)</summary>
            Static,
            /// <summary>glibc / musl など Android 以外の Linux 用</summary>
            ForeignLibc,
            /// <summary>共有ライブラリ (.so) など、単体では実行しないもの</summary>
            SharedObject,
            /// <summary>#! で始まるシェルスクリプト等</summary>
            Script,
            /// <summary>Windows の .exe / .dll (PE 形式)</summary>
            WindowsPe,
            /// <summary>ZIP (APK など)</summary>
            Zip,
            Unknown
        }

        public Kind kind = Kind.Unknown;
        public bool is64;
        public int machine;
        /// <summary>PT_INTERP の中身 (動的リンクのローダのパス)。無ければ null</summary>
        public string interpreter;
        /// <summary>スクリプトの #! 行</summary>
        public string shebang;

        const int PT_DYNAMIC = 2;
        const int PT_INTERP = 3;
        const int ET_EXEC = 2;
        const int ET_DYN = 3;

        public static ElfInfo Read(string path)
        {
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                return Read(fs);
            }
        }

        public static ElfInfo Read(Stream raf)
        {
            var info = new ElfInfo();
            var h = new byte[64];
            int n = ReadUpTo(raf, h);
            if (n >= 2 && h[0] == '#' && h[1] == '!')
            {
                info.kind = Kind.Script;
                string s = Encoding.GetEncoding(28591).GetString(h, 2, n - 2);
                int nl = s.IndexOf('\n');
                info.shebang = (nl >= 0 ? s.Substring(0, nl) : s).Trim();
                return info;
            }
            if (n >= 2 && h[0] == 'M' && h[1] == 'Z')
            {
                info.kind = Kind.WindowsPe;
                return info;
            }
            if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4)
            {
                info.kind = Kind.Zip;
                return info;
            }
            if (n < 52 || h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F')
            {
                return info;
            }
            info.is64 = h[4] == 2;
            bool le = h[5] != 2;
            int type = U16(h, 16, le);
            info.machine = U16(h, 18, le);

            long phoff;
            int phentsize, phnum;
            if (info.is64)
            {
                phoff = U64(h, 32, le);
                phentsize = U16(h, 54, le);
                phnum = U16(h, 56, le);
            }
            else
            {
                phoff = U32(h, 28, le);
                phentsize = U16(h, 42, le);
                phnum = U16(h, 44, le);
            }

            bool hasDynamic = false;
            bool pieFlag = false;
            int minEnt = info.is64 ? 56 : 32;
            if (phentsize >= minEnt && phnum > 0 && phnum < 4096)
            {
                var ph = new byte[phentsize];
                for (int i = 0; i < phnum; i++)
                {
                    raf.Seek(phoff + (long)i * phentsize, SeekOrigin.Begin);
                    if (ReadUpTo(raf, ph) < minEnt) break;
                    int pType = (int)U32(ph, 0, le);
                    if (pType == PT_DYNAMIC)
                    {
                        hasDynamic = true;
                        long off = info.is64 ? U64(ph, 8, le) : U32(ph, 4, le);
                        long size = info.is64 ? U64(ph, 32, le) : U32(ph, 16, le);
                        pieFlag = HasPieFlag(raf, off, size, info.is64, le);
                    }
                    else if (pType == PT_INTERP)
                    {
                        long off = info.is64 ? U64(ph, 8, le) : U32(ph, 4, le);
                        long size = info.is64 ? U64(ph, 32, le) : U32(ph, 16, le);
                        if (size > 0 && size < 4096)
                        {
                            var b = new byte[(int)size];
                            raf.Seek(off, SeekOrigin.Begin);
                            int got = ReadUpTo(raf, b);
                            int end = 0;
                            while (end < got && b[end] != 0) end++;
                            info.interpreter = Encoding.GetEncoding(28591).GetString(b, 0, end);
                        }
                    }
                }
            }

            if (info.interpreter != null)
            {
                info.kind = IsBionicLinker(info.interpreter) ? Kind.BionicDynamic : Kind.ForeignLibc;
            }
            else if (type == ET_EXEC)
            {
                info.kind = Kind.Static;
            }
            else if (type == ET_DYN)
            {
                // ローダ指定なしの ET_DYN は .so か static-pie。static-pie はリンカが DF_1_PIE を立てる
                info.kind = (hasDynamic && !pieFlag) ? Kind.SharedObject : Kind.Static;
            }
            return info;
        }

        /// <summary>動的セクションの DT_FLAGS_1 に DF_1_PIE (位置独立実行ファイルの印) があるか</summary>
        static bool HasPieFlag(Stream raf, long off, long size, bool is64, bool le)
        {
            const long DT_FLAGS_1 = 0x6ffffffbL;
            const long DF_1_PIE = 0x08000000L;
            int ent = is64 ? 16 : 8;
            if (size <= 0 || size > (1 << 20)) return false;
            var d = new byte[(int)size];
            raf.Seek(off, SeekOrigin.Begin);
            int got = ReadUpTo(raf, d);
            for (int o = 0; o + ent <= got; o += ent)
            {
                long tag = is64 ? U64(d, o, le) : U32(d, o, le);
                long val = is64 ? U64(d, o + 8, le) : U32(d, o + 4, le);
                if (tag == 0) break; // DT_NULL
                if (tag == DT_FLAGS_1) return (val & DF_1_PIE) != 0;
            }
            return false;
        }

        static bool IsBionicLinker(string interp)
        {
            return interp == "/system/bin/linker"
                || interp == "/system/bin/linker64"
                || interp == "/system/bin/linker_asan"
                || interp == "/system/bin/linker_asan64"
                || interp.StartsWith("/apex/com.android.runtime/bin/linker", StringComparison.Ordinal);
        }

        public bool IsMusl
        {
            get { return interpreter != null && interpreter.Contains("ld-musl"); }
        }

        /// <summary>ELF の e_machine を Android の ABI 名に直す。対応外は null</summary>
        public string Abi()
        {
            switch (machine)
            {
                case 3: return "x86";
                case 40: return "armeabi-v7a";
                case 62: return "x86_64";
                case 183: return "arm64-v8a";
                case 243: return is64 ? "riscv64" : null;
                default: return null;
            }
        }

        public string MachineName()
        {
            string abi = Abi();
            if (abi != null) return abi;
            switch (machine)
            {
                case 8: return "MIPS";
                case 20: return "PowerPC";
                case 21: return "PowerPC64";
                case 22: return "s390";
                case 258: return "LoongArch";
                default: return "不明なCPU (e_machine=" + machine + ")";
            }
        }

        public bool IsElf()
        {
            return kind == Kind.BionicDynamic || kind == Kind.Static
                || kind == Kind.ForeignLibc || kind == Kind.SharedObject;
        }

        static int ReadUpTo(Stream s, byte[] buf)
        {
            int total = 0;
            while (total < buf.Length)
            {
                int r = s.Read(buf, total, buf.Length - total);
                if (r <= 0) break;
                total += r;
            }
            return total;
        }

        static int U16(byte[] b, int o, bool le)
        {
            int a = b[o], c = b[o + 1];
            return le ? (a | c << 8) : (a << 8 | c);
        }

        static long U32(byte[] b, int o, bool le)
        {
            long v = 0;
            for (int i = 0; i < 4; i++)
            {
                int idx = le ? o + 3 - i : o + i;
                v = (v << 8) | b[idx];
            }
            return v;
        }

        static long U64(byte[] b, int o, bool le)
        {
            long v = 0;
            for (int i = 0; i < 8; i++)
            {
                int idx = le ? o + 7 - i : o + i;
                v = (v << 8) | b[idx];
            }
            return v;
        }
    }
}
