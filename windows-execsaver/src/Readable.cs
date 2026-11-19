using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using Iced.Intel;
using ICSharpCode.Decompiler;
using ICSharpCode.Decompiler.CSharp;

namespace ExecSaver
{
    public sealed class Converted { public byte[] Data; public string Ext; public string Label; }

    /// <summary>
    /// exe に入っているライブラリ (ILSpy のエンジン、Iced 逆アセンブラー) を読み込む。
    /// 1 ファイルで配れるよう、DLL は gzip して exe のリソースに入れてある。
    /// </summary>
    static class Embedded
    {
        static readonly Dictionary<string, Assembly> loaded = new Dictionary<string, Assembly>(StringComparer.OrdinalIgnoreCase);

        public static void Install() { AppDomain.CurrentDomain.AssemblyResolve += Resolve; }

        static Assembly Resolve(object sender, ResolveEventArgs e)
        {
            string name = new AssemblyName(e.Name).Name;
            lock (loaded)
            {
                Assembly a;
                if (loaded.TryGetValue(name, out a)) return a;
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("lib/" + name + ".dll.gz"))
                {
                    if (s == null) return null;
                    using (var gz = new GZipStream(s, CompressionMode.Decompress))
                    using (var ms = new MemoryStream())
                    {
                        gz.CopyTo(ms);
                        a = Assembly.Load(ms.ToArray());
                    }
                }
                loaded[name] = a;
                return a;
            }
        }
    }

    /// <summary>バイナリを、人が読めるテキストのコードに変換する。</summary>
    public static class Readable
    {
        public static int MaxInstructions = 300000;

        /// <summary>data はテキストでないこと。sourcePath は元ファイル (.NET の参照 DLL を探すのに使う、無くてもよい)。</summary>
        public static Converted Convert(byte[] data, string ext, string label, string name, string sourcePath, string hash)
        {
            var pe = PeInfo.TryParse(data);
            if (pe != null && pe.IsDotNet)
            {
                try { return DotNet(data, pe, label, name, sourcePath, hash); }
                catch (Exception ex) { return Report(data, label, name, hash, pe, "C# への変換に失敗しました: " + ex.GetType().Name + ": " + ex.Message); }
            }
            if (pe != null && (pe.Machine == 0x14c || pe.Machine == 0x8664))
            {
                try { return Asm(data, pe, label, name, hash); }
                catch (Exception ex) { return Report(data, label, name, hash, pe, "逆アセンブルに失敗しました: " + ex.Message); }
            }
            if (data.Length >= 4 && data[0] == 0x50 && data[1] == 0x4B && data[2] == 3 && data[3] == 4)
            {
                try { return Zip(data, ext, label, name, hash); } catch { }
            }
            return Report(data, label, name, hash, pe, pe != null ? "この CPU (0x" + pe.Machine.ToString("x") + ") の逆アセンブルには対応していません" : null);
        }

        static string Head(string c, string name, string label, string hash, string how)
        {
            var sb = new StringBuilder();
            sb.Append(c).Append(" ==========================================================\r\n");
            sb.Append(c).Append(" 元のファイル: ").Append(name).Append("  (").Append(label).Append(")\r\n");
            sb.Append(c).Append(" SHA256: ").Append(hash).Append("\r\n");
            sb.Append(c).Append(" 保存日時: ").Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")).Append("\r\n");
            foreach (var line in how.Split('\n')) sb.Append(c).Append(" ").Append(line.TrimEnd('\r')).Append("\r\n");
            sb.Append(c).Append(" ==========================================================\r\n\r\n");
            return sb.ToString();
        }

        static byte[] Utf8(string s) { return new UTF8Encoding(true).GetPreamble().Concat(Encoding.UTF8.GetBytes(s)).ToArray(); }

        // ---------------- .NET → C# ----------------
        static Converted DotNet(byte[] data, PeInfo pe, string label, string name, string sourcePath, string hash)
        {
            string path = sourcePath, tmp = null;
            if (path == null || !File.Exists(path))
            {
                tmp = Path.Combine(Path.GetTempPath(), "execsaver_" + Guid.NewGuid().ToString("N") + ".dll");
                File.WriteAllBytes(tmp, data); path = tmp;
            }
            try
            {
                var settings = new DecompilerSettings { ThrowOnAssemblyResolveErrors = false, ShowXmlDocumentation = false };
                var d = new CSharpDecompiler(path, settings);
                string code = d.DecompileWholeModuleAsString();
                return new Converted
                {
                    Ext = ".cs", Label = "C# (逆コンパイル)",
                    Data = Utf8(Head("//", name, label, hash,
                        ".NET のプログラムなので、ILSpy のエンジンで C# のソースコードに戻しました。\n" +
                        "変数名などは元と違うことがありますが、処理の内容はこの通りです。") + code)
                };
            }
            finally { if (tmp != null) try { File.Delete(tmp); } catch { } }
        }

        // ---------------- 機械語 → アセンブリ ----------------
        static Converted Asm(byte[] data, PeInfo pe, string label, string name, string hash)
        {
            int bitness = pe.Machine == 0x8664 ? 64 : 32;
            var strings = Strings(data, 4);
            var strAt = new Dictionary<ulong, string>();
            foreach (var s in strings) { ulong va; if (pe.OffsetToVa(s.Key, out va) && !strAt.ContainsKey(va)) strAt[va] = s.Value; }

            var labels = new Dictionary<ulong, string>();
            foreach (var kv in pe.Exports) labels[kv.Key] = kv.Value;
            labels[pe.ImageBase + pe.EntryRva] = "start";
            var code = pe.Sections.Where(s => (s.Characteristics & 0x20000000) != 0 && s.RawSize > 0).ToList();

            // 1 回目: 呼び出し先・ジャンプ先を集めてラベルにする
            var all = new List<Instruction>();
            bool truncated = false;
            foreach (var sec in code)
            {
                int len = (int)Math.Min(sec.RawSize, Math.Max(sec.VirtualSize, 1));
                len = Math.Min(len, data.Length - (int)sec.RawPtr);
                if (len <= 0) continue;
                var dec = Iced.Intel.Decoder.Create(bitness, new ByteArrayCodeReader(data, (int)sec.RawPtr, len));
                ulong start = pe.ImageBase + sec.Va;
                dec.IP = start;
                ulong end = start + (ulong)len;
                while (dec.IP < end)
                {
                    if (all.Count >= MaxInstructions) { truncated = true; break; }
                    Instruction ins;
                    dec.Decode(out ins);
                    all.Add(ins);
                    if (ins.IsInvalid) continue;
                    var k = ins.Op0Kind;
                    if (k == OpKind.NearBranch64 || k == OpKind.NearBranch32 || k == OpKind.NearBranch16)
                    {
                        ulong t = ins.NearBranchTarget;
                        if (ins.FlowControl == FlowControl.Call) { if (!labels.ContainsKey(t) || labels[t].StartsWith("loc_")) labels[t] = "sub_" + t.ToString("x"); }
                        else if (!labels.ContainsKey(t)) labels[t] = "loc_" + t.ToString("x");
                    }
                }
                if (truncated) break;
            }

            var fmt = new MasmFormatter();
            fmt.Options.HexPrefix = "0x"; fmt.Options.HexSuffix = null; fmt.Options.UppercaseHex = false;
            fmt.Options.SpaceAfterOperandSeparator = true; fmt.Options.BranchLeadingZeros = false; fmt.Options.FirstOperandCharIndex = 8;
            var output = new StringOutput();

            var sb = new StringBuilder();
            sb.Append(Head(";", name, label, hash,
                "機械語のプログラム (C/C++ などで作られたもの) は、元のソースコードには戻せません。\n" +
                "そのため CPU の命令を 1 つずつ読めるようにした「アセンブリ言語」で保存しています。\n" +
                "どんな Windows の機能を使うかは、下の「使っている Windows の機能」と、命令の横の __imp_ が手がかりになります。"));
            sb.Append("; 形式: ").Append(bitness).Append("bit ").Append(pe.Kind).Append("   イメージベース: 0x").Append(pe.ImageBase.ToString("x"))
              .Append("   エントリーポイント: 0x").Append((pe.ImageBase + pe.EntryRva).ToString("x")).Append("\r\n;\r\n");
            sb.Append("; ---- セクション ----\r\n");
            foreach (var s in pe.Sections)
                sb.AppendFormat(";   {0,-8} 0x{1:x}  大きさ {2,9:N0}  {3}\r\n", s.Name, pe.ImageBase + s.Va, Math.Max(s.VirtualSize, s.RawSize),
                    ((s.Characteristics & 0x20000000) != 0 ? "実行" : "") + ((s.Characteristics & 0x40000000) != 0 ? " 読込" : "") + ((s.Characteristics & 0x80000000) != 0 ? " 書込" : ""));
            sb.Append(";\r\n; ---- 使っている Windows の機能 (インポート) ----\r\n");
            foreach (var g in pe.Imports.Values.GroupBy(i => i.Dll, StringComparer.OrdinalIgnoreCase))
            {
                sb.Append(";   ").Append(g.Key).Append("\r\n");
                foreach (var i in g) sb.Append(";       ").Append(i.Func).Append("\r\n");
            }
            if (pe.Exports.Count > 0)
            {
                sb.Append(";\r\n; ---- ほかのプログラムに提供している関数 (エクスポート) ----\r\n");
                foreach (var kv in pe.Exports.OrderBy(k => k.Key)) sb.Append(";   0x").Append(kv.Key.ToString("x")).Append("  ").Append(kv.Value).Append("\r\n");
            }
            var shown = strings.Where(s => s.Value.Length >= 6).Take(3000).ToList();
            sb.Append(";\r\n; ---- 埋め込まれた文字列 (").Append(shown.Count).Append(" 個") .Append(strings.Count > shown.Count ? "、ほかは省略" : "").Append(") ----\r\n");
            foreach (var s in shown) { ulong va; pe.OffsetToVa(s.Key, out va); sb.Append(";   ").Append(va != 0 ? "0x" + va.ToString("x") : "(ファイル 0x" + s.Key.ToString("x") + ")").Append("  ").Append(Quote(s.Value)).Append("\r\n"); }
            sb.Append(";\r\n; ---- コード ----\r\n");
            if (truncated) sb.Append("; ※ 長すぎるため最初の ").Append(MaxInstructions.ToString("N0")).Append(" 命令までにしています\r\n");

            ulong prevEnd = 0;
            foreach (var ins0 in all)
            {
                var ins = ins0;
                if (ins.IP != prevEnd && prevEnd != 0) sb.Append("\r\n");
                prevEnd = ins.NextIP;
                string lbl;
                if (labels.TryGetValue(ins.IP, out lbl))
                {
                    if (!lbl.StartsWith("loc_")) sb.Append("\r\n; ======== 関数 ========\r\n");
                    sb.Append(lbl).Append(":\r\n");
                }
                if (ins.IsInvalid) { sb.Append("        ").Append(ins.IP.ToString("x")).Append("  db ?   ; 命令ではないデータ\r\n"); continue; }
                fmt.Format(ref ins, output);
                string text = output.ToStringAndReset();
                string comment = null;
                foreach (ulong a in Addresses(ins))
                {
                    ImportEntry imp; string l2, s2;
                    if (pe.Imports.TryGetValue(a, out imp)) { text = ReplaceHex(text, a, "__imp_" + imp.Func); comment = imp.Dll + " の " + imp.Func; }
                    else if (labels.TryGetValue(a, out l2)) text = ReplaceHex(text, a, l2);
                    else if (strAt.TryGetValue(a, out s2)) comment = Quote(s2.Length > 120 ? s2.Substring(0, 120) + "…" : s2);
                }
                sb.Append("        ").Append(ins.IP.ToString("x")).Append("  ").Append(text);
                if (comment != null) sb.Append(new string(' ', Math.Max(1, 48 - text.Length))).Append("; ").Append(comment);
                sb.Append("\r\n");
                if (ins.FlowControl == FlowControl.Return || ins.FlowControl == FlowControl.UnconditionalBranch) sb.Append("\r\n");
            }
            return new Converted { Ext = ".asm", Label = "アセンブリ (逆アセンブル)", Data = Utf8(sb.ToString()) };
        }

        static IEnumerable<ulong> Addresses(Instruction ins)
        {
            for (int i = 0; i < ins.OpCount; i++)
            {
                switch (ins.GetOpKind(i))
                {
                    case OpKind.NearBranch16: case OpKind.NearBranch32: case OpKind.NearBranch64: yield return ins.NearBranchTarget; break;
                    case OpKind.Memory:
                        if (ins.IsIPRelativeMemoryOperand) yield return ins.IPRelativeMemoryAddress;
                        else if (ins.MemoryBase == Register.None && ins.MemoryIndex == Register.None) yield return ins.MemoryDisplacement64;
                        break;
                    case OpKind.Immediate32: yield return ins.Immediate32; break;
                    case OpKind.Immediate32to64: case OpKind.Immediate64: yield return ins.Immediate64; break;
                }
            }
        }

        static string ReplaceHex(string text, ulong a, string name)
        {
            string hex = "0x" + a.ToString("x");
            int i = 0;
            while ((i = text.IndexOf(hex, i, StringComparison.Ordinal)) >= 0)
            {
                int e = i + hex.Length;
                if (e < text.Length && Uri.IsHexDigit(text[e])) { i = e; continue; }
                return text.Substring(0, i) + name + text.Substring(e);
            }
            return text;
        }

        static string Quote(string s)
        {
            return "\"" + s.Replace("\\", "\\\\").Replace("\r", "\\r").Replace("\n", "\\n").Replace("\t", "\\t").Replace("\"", "\\\"") + "\"";
        }

        /// <summary>ASCII と UTF-16 の文字列を探す (ファイル内の位置 → 文字列)。</summary>
        public static List<KeyValuePair<long, string>> Strings(byte[] d, int min)
        {
            var list = new List<KeyValuePair<long, string>>();
            int start = -1;
            for (int i = 0; i <= d.Length; i++)
            {
                bool ok = i < d.Length && (d[i] >= 0x20 && d[i] < 0x7F || d[i] == 9);
                if (ok) { if (start < 0) start = i; }
                else if (start >= 0) { if (i - start >= min) list.Add(new KeyValuePair<long, string>(start, Encoding.ASCII.GetString(d, start, Math.Min(i - start, 1000)))); start = -1; }
            }
            for (int align = 0; align < 2; align++)
            {
                start = -1;
                var sb = new StringBuilder();
                for (int i = align; i + 1 <= d.Length; i += 2)
                {
                    char c = i + 1 < d.Length ? (char)(d[i] | d[i + 1] << 8) : '\0';
                    bool ok = (c >= 0x20 && c < 0x7F) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF) || c == 9;
                    if (ok && i + 1 < d.Length) { if (start < 0) { start = i; sb.Clear(); } sb.Append(c); }
                    else if (start >= 0) { if (sb.Length >= min) list.Add(new KeyValuePair<long, string>(start, sb.Length > 1000 ? sb.ToString(0, 1000) : sb.ToString())); start = -1; }
                }
            }
            list.Sort((a, b) => a.Key.CompareTo(b.Key));
            return list;
        }

        // ---------------- ZIP / JAR / APK / Office ----------------
        static Converted Zip(byte[] data, string ext, string label, string name, string hash)
        {
            var sb = new StringBuilder(Head("", name, label, hash, "圧縮ファイルなので、中身の一覧と、中のテキスト (スクリプトなど) をまとめています。"));
            long budget = 8L * 1024 * 1024;
            using (var z = new ZipArchive(new MemoryStream(data), ZipArchiveMode.Read))
            {
                sb.Append("---- 中身 (").Append(z.Entries.Count).Append(" 個) ----\r\n");
                foreach (var e in z.Entries) sb.AppendFormat("{0,12:N0}  {1}\r\n", e.Length, e.FullName);
                foreach (var e in z.Entries)
                {
                    if (e.Length == 0 || e.Length > 1024 * 1024 || budget <= 0) continue;
                    byte[] b;
                    using (var s = e.Open()) using (var ms = new MemoryStream()) { s.CopyTo(ms); b = ms.ToArray(); }
                    string t = Format.AsText(b);
                    if (t == null) continue;
                    budget -= b.Length;
                    sb.Append("\r\n\r\n======== ").Append(e.FullName).Append(" ========\r\n").Append(t);
                }
            }
            return new Converted { Ext = ".txt", Label = "中身の一覧とテキスト", Data = Utf8(sb.ToString()) };
        }

        // ---------------- そのほかのバイナリ ----------------
        static Converted Report(byte[] data, string label, string name, string hash, PeInfo pe, string note)
        {
            var sb = new StringBuilder(Head("", name, label, hash, "プログラムのコードとしては読めない形式なので、埋め込まれた文字列と、先頭の中身 (16 進数) をまとめています。" + (note != null ? "\n" + note : "")));
            if (pe != null)
            {
                sb.Append("---- 使っている Windows の機能 (インポート) ----\r\n");
                foreach (var i in pe.Imports.Values) sb.Append("  ").Append(i.Dll).Append("  ").Append(i.Func).Append("\r\n");
                sb.Append("\r\n");
            }
            var strings = Strings(data, 5);
            sb.Append("---- 埋め込まれた文字列 ----\r\n");
            foreach (var s in strings.Take(5000)) sb.Append("  0x").Append(s.Key.ToString("x6")).Append("  ").Append(Quote(s.Value)).Append("\r\n");
            sb.Append("\r\n---- 先頭 4KB (16 進数) ----\r\n");
            for (int off = 0; off < Math.Min(data.Length, 4096); off += 16)
            {
                sb.Append(off.ToString("x8")).Append("  ");
                var asc = new StringBuilder();
                for (int k = 0; k < 16; k++)
                {
                    if (off + k < data.Length) { byte b = data[off + k]; sb.Append(b.ToString("x2")).Append(' '); asc.Append(b >= 0x20 && b < 0x7F ? (char)b : '.'); }
                    else sb.Append("   ");
                }
                sb.Append(' ').Append(asc).Append("\r\n");
            }
            return new Converted { Ext = ".txt", Label = "中身の解析", Data = Utf8(sb.ToString()) };
        }
    }

    public sealed class ImportEntry { public string Dll, Func; }

    /// <summary>PE (exe / dll) のヘッダー・セクション・インポート・エクスポートを読む。</summary>
    public sealed class PeInfo
    {
        public sealed class Section { public string Name; public uint Va, VirtualSize, RawPtr, RawSize, Characteristics; }
        public int Machine;
        public bool Pe64, IsDotNet;
        public string Kind;
        public ulong ImageBase;
        public uint EntryRva;
        public readonly List<Section> Sections = new List<Section>();
        public readonly Dictionary<ulong, ImportEntry> Imports = new Dictionary<ulong, ImportEntry>();   // IAT の場所 → 関数
        public readonly Dictionary<ulong, string> Exports = new Dictionary<ulong, string>();
        byte[] d;

        public static PeInfo TryParse(byte[] data)
        {
            try { var p = new PeInfo(); return p.Parse(data) ? p : null; } catch { return null; }
        }

        int U16(long o) { return BitConverter.ToUInt16(d, (int)o); }
        uint U32(long o) { return BitConverter.ToUInt32(d, (int)o); }

        bool Parse(byte[] data)
        {
            d = data;
            if (d.Length < 0x40 || d[0] != 'M' || d[1] != 'Z') return false;
            long pe = U32(0x3C);
            if (pe <= 0 || pe + 24 > d.Length || U32(pe) != 0x00004550) return false;
            Machine = U16(pe + 4);
            int nsec = U16(pe + 6), optSize = U16(pe + 20), chars = U16(pe + 22);
            long opt = pe + 24;
            Pe64 = U16(opt) == 0x20b;
            EntryRva = U32(opt + 16);
            ImageBase = Pe64 ? BitConverter.ToUInt64(d, (int)opt + 24) : U32(opt + 28);
            int subsystem = U16(opt + 68);
            Kind = (chars & 0x2000) != 0 ? "DLL" : subsystem == 1 ? "ドライバー" : subsystem == 2 ? "GUI アプリ" : subsystem == 3 ? "コンソール アプリ" : "実行ファイル";
            long dirs = opt + (Pe64 ? 112 : 96);
            int ndirs = (int)U32(opt + (Pe64 ? 108 : 92));
            long sh = opt + optSize;
            for (int i = 0; i < nsec && sh + 40 * (i + 1) <= d.Length; i++)
            {
                long o = sh + 40 * i;
                Sections.Add(new Section
                {
                    Name = Encoding.ASCII.GetString(d, (int)o, 8).TrimEnd('\0'),
                    VirtualSize = U32(o + 8), Va = U32(o + 12), RawSize = U32(o + 16), RawPtr = U32(o + 20), Characteristics = U32(o + 36)
                });
            }
            Func<int, uint> dirRva = i => i < ndirs && dirs + 8 * i + 8 <= d.Length ? U32(dirs + 8 * i) : 0;
            IsDotNet = dirRva(14) != 0;
            try { ReadImports(dirRva(1), false); } catch { }
            try { ReadImports(dirRva(13), true); } catch { }
            try { ReadExports(dirRva(0)); } catch { }
            return true;
        }

        public long RvaToOffset(uint rva)
        {
            foreach (var s in Sections)
                if (rva >= s.Va && rva < s.Va + Math.Max(s.VirtualSize, s.RawSize))
                {
                    long off = s.RawPtr + (rva - s.Va);
                    return rva - s.Va < s.RawSize && off < d.Length ? off : -1;
                }
            return rva < d.Length ? (long)rva : -1;
        }

        public bool OffsetToVa(long off, out ulong va)
        {
            foreach (var s in Sections)
                if (s.RawSize > 0 && off >= s.RawPtr && off < s.RawPtr + s.RawSize) { va = ImageBase + s.Va + (ulong)(off - s.RawPtr); return true; }
            va = 0; return false;
        }

        string CStr(uint rva)
        {
            long o = RvaToOffset(rva);
            if (o < 0) return "?";
            int e = (int)o;
            while (e < d.Length && d[e] != 0 && e - o < 512) e++;
            return Encoding.ASCII.GetString(d, (int)o, e - (int)o);
        }

        void ReadImports(uint rva, bool delay)
        {
            if (rva == 0) return;
            int size = delay ? 32 : 20, ptr = Pe64 ? 8 : 4;
            for (int n = 0; n < 4096; n++)
            {
                long o = RvaToOffset((uint)(rva + n * size));
                if (o < 0 || o + size > d.Length) break;
                uint nameRva, iat, intRva;
                if (delay) { nameRva = U32(o + 4); iat = U32(o + 12); intRva = U32(o + 16); if ((U32(o) & 1) == 0 && nameRva != 0) { ulong b = ImageBase; nameRva = (uint)(nameRva - b); iat = (uint)(iat - b); intRva = (uint)(intRva - b); } }
                else { intRva = U32(o); nameRva = U32(o + 12); iat = U32(o + 16); }
                if (nameRva == 0 && iat == 0) break;
                string dll = CStr(nameRva);
                uint table = intRva != 0 ? intRva : iat;
                for (int k = 0; k < 65536; k++)
                {
                    long t = RvaToOffset((uint)(table + k * ptr));
                    if (t < 0 || t + ptr > d.Length) break;
                    ulong v = Pe64 ? BitConverter.ToUInt64(d, (int)t) : U32(t);
                    if (v == 0) break;
                    bool ordinal = Pe64 ? (v >> 63) != 0 : (v >> 31) != 0;
                    string func = ordinal ? "#" + (v & 0xFFFF) : CStr((uint)v + 2);
                    Imports[ImageBase + iat + (ulong)(k * ptr)] = new ImportEntry { Dll = dll, Func = func };
                }
            }
        }

        void ReadExports(uint rva)
        {
            if (rva == 0) return;
            long o = RvaToOffset(rva);
            if (o < 0) return;
            uint nNames = U32(o + 24), funcs = U32(o + 28), names = U32(o + 32), ords = U32(o + 36);
            for (uint i = 0; i < nNames && i < 100000; i++)
            {
                long no = RvaToOffset(names + i * 4), oo = RvaToOffset(ords + i * 2);
                if (no < 0 || oo < 0) break;
                int ord = U16(oo);
                long fo = RvaToOffset((uint)(funcs + ord * 4));
                if (fo < 0) break;
                Exports[ImageBase + U32(fo)] = CStr(U32(no));
            }
        }
    }
}
