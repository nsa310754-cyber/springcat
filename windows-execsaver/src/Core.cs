using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace ExecSaver
{
    /// <summary>Windows のコマンドラインを区切る (CommandLineToArgvW と同じ規則)。位置も覚える。</summary>
    public static class CmdLine
    {
        public sealed class Tok { public string Text; public int Start; public override string ToString() { return Text; } }

        public static List<Tok> Split(string s)
        {
            var list = new List<Tok>();
            if (string.IsNullOrEmpty(s)) return list;
            int i = 0, n = s.Length;
            while (true)
            {
                while (i < n && (s[i] == ' ' || s[i] == '\t')) i++;
                if (i >= n) break;
                var t = new Tok { Start = i };
                var sb = new StringBuilder();
                bool q = false;
                while (i < n)
                {
                    char c = s[i];
                    if (!q && (c == ' ' || c == '\t')) break;
                    if (c == '\\')
                    {
                        int bs = 0;
                        while (i < n && s[i] == '\\') { bs++; i++; }
                        if (i < n && s[i] == '"')
                        {
                            sb.Append('\\', bs / 2);
                            if (bs % 2 == 1) { sb.Append('"'); i++; }
                        }
                        else sb.Append('\\', bs);
                        continue;
                    }
                    if (c == '"')
                    {
                        if (q && i + 1 < n && s[i + 1] == '"') { sb.Append('"'); i += 2; continue; }
                        q = !q; i++; continue;
                    }
                    sb.Append(c); i++;
                }
                t.Text = sb.ToString();
                list.Add(t);
            }
            return list;
        }
    }

    /// <summary>実際に実行された「コード」。ファイルか、コマンドラインに直接書かれたコード。</summary>
    public sealed class Target
    {
        public string Path;          // ファイルのとき
        public byte[] Inline;        // コマンドラインに書かれたコードのとき
        public string Name;          // 保存フォルダー・ファイルの名前
        public string HintExt;       // 元の拡張子 (形式が判定できないときに使う)
        public string Host;          // 実行したプログラム (cmd, powershell など)
        public string Note;          // 見つからなかった理由など
        public bool IsInline { get { return Inline != null; } }
        public override string ToString() { return (IsInline ? "inline:" + Name : Path) + " [" + Host + "]"; }
    }

    /// <summary>実行されたプログラムとコマンドラインから、本当に実行されたコードを割り出す。</summary>
    public static class Resolver
    {
        /// <summary>\ と / のどちらの区切りでも、拡張子なしのファイル名を返す。</summary>
        public static string BaseName(string p)
        {
            p = p ?? "";
            int i = Math.Max(p.LastIndexOf('\\'), p.LastIndexOf('/'));
            string f = p.Substring(i + 1);
            int dot = f.LastIndexOf('.');
            return dot > 0 ? f.Substring(0, dot) : f;
        }

        public static Target Resolve(string exePath, string cmdLine, string cwd)
        {
            string host = BaseName(exePath).ToLowerInvariant();
            var toks = CmdLine.Split(cmdLine ?? "");
            if (toks.Count > 0) toks.RemoveAt(0);      // 先頭はプログラム自身
            Target t = null;
            try
            {
                switch (host)
                {
                    case "cmd": t = Cmd(cmdLine, toks, cwd); break;
                    case "powershell": case "pwsh": case "powershell_ise": t = PowerShell(cmdLine, toks, cwd); break;
                    case "wscript": case "cscript": t = FirstArg(toks, cwd, null, a => a.StartsWith("//"), new[] { ".vbs", ".js" }); break;
                    case "mshta": t = Mshta(toks, cwd); break;
                    case "python": case "pythonw": case "py": case "python3": t = Interp(toks, cwd, "-c", ".py", new[] { "-W", "-X", "-Q" }, "-m"); break;
                    case "node": t = Interp(toks, cwd, "-e|--eval|-p|--print", ".js", new[] { "-r", "--require" }, null); break;
                    case "ruby": t = Interp(toks, cwd, "-e", ".rb", new[] { "-I", "-r" }, null); break;
                    case "perl": t = Interp(toks, cwd, "-e|-E", ".pl", new[] { "-I", "-M" }, null); break;
                    case "php": t = Interp(toks, cwd, "-r", ".php", new[] { "-c", "-d" }, null); break;
                    case "bash": case "sh": t = Interp(toks, cwd, "-c", ".sh", new string[0], null); break;
                    case "java": case "javaw":
                        for (int i = 0; i + 1 < toks.Count; i++)
                            if (toks[i].Text == "-jar") { t = FileTarget(toks[i + 1].Text, cwd, null); break; }
                        break;
                    case "rundll32":
                        if (toks.Count > 0)
                        {
                            string a = toks[0].Text;
                            int comma = a.LastIndexOf(',');
                            if (comma > 0) a = a.Substring(0, comma);
                            t = FileTarget(a.Trim(), cwd, new[] { ".dll" }, true);
                        }
                        break;
                    case "regsvr32": t = FirstArg(toks, cwd, null, a => (a.StartsWith("/") || a.StartsWith("-")) && !File.Exists(a), new[] { ".dll" }, true); break;
                    case "msiexec":
                        for (int i = 0; i + 1 < toks.Count; i++)
                        {
                            string o = toks[i].Text.ToLowerInvariant();
                            if (o == "/i" || o == "/package" || o == "/x" || o == "/a" || o == "-i") { t = FileTarget(toks[i + 1].Text, cwd, new[] { ".msi" }); break; }
                        }
                        break;
                }
            }
            catch { t = null; }
            if (t == null)
            {
                // 普通のプログラム: 実行ファイルそのもの
                t = new Target { Path = exePath, HintExt = System.IO.Path.GetExtension(exePath ?? "") };
                t.Name = BaseName(exePath);
            }
            t.Host = host;
            return t;
        }

        static string Unquote(string s)
        {
            s = s.Trim();
            if (s.Length >= 2 && s[0] == '"' && s[s.Length - 1] == '"') s = s.Substring(1, s.Length - 2);
            return s;
        }

        static string Expand(string s) { return Environment.ExpandEnvironmentVariables(Unquote(s)); }

        /// <summary>パスを実在するファイルに直す。見つからなければ null。</summary>
        public static string Locate(string p, string cwd, string[] tryExts)
        {
            if (string.IsNullOrWhiteSpace(p)) return null;
            p = Expand(p);
            if (p.IndexOfAny(System.IO.Path.GetInvalidPathChars()) >= 0) return null;
            var cands = new List<string>();
            if (System.IO.Path.IsPathRooted(p)) cands.Add(p);
            else
            {
                if (!string.IsNullOrEmpty(cwd)) cands.Add(System.IO.Path.Combine(cwd, p));
                cands.Add(System.IO.Path.GetFullPath(p));
            }
            foreach (var c in cands.ToList())
                if (tryExts != null) foreach (var e in tryExts) cands.Add(c + e);
            foreach (var c in cands) if (File.Exists(c)) return System.IO.Path.GetFullPath(c);
            return null;
        }

        static Target FileTarget(string p, string cwd, string[] tryExts, bool mustExist = false)
        {
            string found = Locate(p, cwd, tryExts);
            string shown = Expand(p);
            return new Target
            {
                Path = found ?? shown,
                HintExt = System.IO.Path.GetExtension(found ?? shown),
                Name = System.IO.Path.GetFileNameWithoutExtension(found ?? shown),
                Note = found == null ? "ファイルが見つかりません: " + shown : null
            };
        }

        static Target InlineTarget(string code, string host, string ext)
        {
            return new Target { Inline = Encoding.UTF8.GetBytes(code), Name = host, HintExt = ext };
        }

        static Target FirstArg(List<CmdLine.Tok> toks, string cwd, string _, Func<string, bool> isOption, string[] tryExts, bool mustExist = false)
        {
            foreach (var tk in toks)
                if (!isOption(tk.Text)) return FileTarget(tk.Text, cwd, tryExts);
            return null;
        }

        static Target Cmd(string raw, List<CmdLine.Tok> toks, string cwd)
        {
            for (int i = 0; i < toks.Count; i++)
            {
                string o = toks[i].Text.ToLowerInvariant();
                if (o == "/c" || o == "/k" || o == "/r")
                {
                    if (i + 1 >= toks.Count) return null;
                    string rest = raw.Substring(toks[i + 1].Start).Trim();
                    // cmd の規則: 全体が "..." で囲まれていれば外す
                    if (rest.Length >= 2 && rest[0] == '"' && rest[rest.Length - 1] == '"') rest = rest.Substring(1, rest.Length - 2);
                    var inner = CmdLine.Split(rest);
                    if (inner.Count > 0)
                    {
                        string first = inner[0].Text;
                        string ext = System.IO.Path.GetExtension(first).ToLowerInvariant();
                        string found = Locate(first, cwd, ext == "" ? new[] { ".bat", ".cmd" } : null);
                        if (found != null && (found.EndsWith(".bat", StringComparison.OrdinalIgnoreCase) || found.EndsWith(".cmd", StringComparison.OrdinalIgnoreCase)))
                            return FileTarget(found, cwd, null);
                        // 別のプログラムを起動するだけ (cmd /c start x.exe など) なら、そのプログラム側で記録される
                        if (found != null && (ext == ".exe" || ext == ".com")) return Skip("cmd から起動");
                    }
                    return InlineTarget(rest, "cmd", ".bat");
                }
            }
            return Skip("対話型のコマンドプロンプト");
        }

        static Target Skip(string why) { return new Target { Note = "SKIP:" + why }; }

        static Target PowerShell(string raw, List<CmdLine.Tok> toks, string cwd)
        {
            var valueOpts = new[] { "executionpolicy", "ep", "windowstyle", "version", "psconsolefile", "inputformat", "outputformat",
                                    "configurationname", "workingdirectory", "wd", "settingsfile", "custompipename" };
            for (int i = 0; i < toks.Count; i++)
            {
                string a = toks[i].Text;
                if (a.StartsWith("-") || (a.StartsWith("/") && !File.Exists(a)))
                {
                    string o = a.TrimStart('-', '/').ToLowerInvariant();
                    if (o == "") continue;
                    if (o.Length >= 2 && ("executionpolicy".StartsWith(o) && o.StartsWith("ex"))) { i++; continue; }
                    if (o == "e" || o == "ec" || (o.Length >= 2 && "encodedcommand".StartsWith(o)))
                    {
                        if (i + 1 >= toks.Count) return null;
                        string code;
                        try { code = Encoding.Unicode.GetString(Convert.FromBase64String(toks[i + 1].Text)); }
                        catch { code = toks[i + 1].Text; }
                        return InlineTarget(code, "powershell", ".ps1");
                    }
                    if ("command".StartsWith(o))
                    {
                        if (i + 1 >= toks.Count) return null;
                        string code = toks.Count == i + 2 ? toks[i + 1].Text : raw.Substring(toks[i + 1].Start).Trim();
                        if (code == "-") return Skip("標準入力からのコマンド");
                        return InlineTarget(code, "powershell", ".ps1");
                    }
                    if ("file".StartsWith(o))
                    {
                        if (i + 1 >= toks.Count) return null;
                        return FileTarget(toks[i + 1].Text, cwd, new[] { ".ps1" });
                    }
                    if (valueOpts.Any(v => v == o || (o.Length >= 3 && v.StartsWith(o))) || o == "w" || o == "v") { i++; continue; }
                    continue;   // -NoProfile などの値なしオプション
                }
                // オプション以外の最初の引数: .ps1 ならファイル、それ以外は -Command と同じ扱い
                if (a.EndsWith(".ps1", StringComparison.OrdinalIgnoreCase)) return FileTarget(a, cwd, null);
                return InlineTarget(raw.Substring(toks[i].Start).Trim(), "powershell", ".ps1");
            }
            return Skip("対話型の PowerShell");
        }

        static Target Mshta(List<CmdLine.Tok> toks, string cwd)
        {
            if (toks.Count == 0) return null;
            string a = toks[0].Text.Trim();
            string low = a.ToLowerInvariant();
            if (low.StartsWith("javascript:")) return InlineTarget(a.Substring(11), "mshta", ".js");
            if (low.StartsWith("vbscript:")) return InlineTarget(a.Substring(9), "mshta", ".vbs");
            if (low.StartsWith("http://") || low.StartsWith("https://"))
                return InlineTarget("[InternetShortcut]\r\nURL=" + a + "\r\n", "mshta", ".url");
            return FileTarget(a, cwd, new[] { ".hta" });
        }

        /// <summary>python / node などのインタープリター。codeOpt の後ろはコード、それ以外は最初の引数がスクリプト。</summary>
        static Target Interp(List<CmdLine.Tok> toks, string cwd, string codeOpts, string ext, string[] valueOpts, string moduleOpt)
        {
            var codes = codeOpts.Split('|');
            for (int i = 0; i < toks.Count; i++)
            {
                string a = toks[i].Text;
                if (codes.Contains(a)) return i + 1 < toks.Count ? InlineTarget(toks[i + 1].Text, Hostless(ext), ext) : null;
                if (moduleOpt != null && a == moduleOpt) return Skip("モジュール実行 (" + (i + 1 < toks.Count ? toks[i + 1].Text : "") + ")");
                if (valueOpts.Contains(a)) { i++; continue; }
                if (a.StartsWith("-")) continue;
                return FileTarget(a, cwd, new[] { ext });
            }
            return Skip("対話モード");
        }

        static string Hostless(string ext)
        {
            switch (ext) { case ".py": return "python"; case ".js": return "node"; case ".rb": return "ruby"; case ".pl": return "perl"; case ".php": return "php"; default: return "sh"; }
        }
    }

    /// <summary>中身から形式 (拡張子) を判定する。</summary>
    public static class Format
    {
        static bool At(byte[] d, int off, params byte[] sig)
        {
            if (d.Length < off + sig.Length) return false;
            for (int i = 0; i < sig.Length; i++) if (d[off + i] != sig[i]) return false;
            return true;
        }

        static bool Contains(byte[] d, string ascii)
        {
            byte[] p = Encoding.ASCII.GetBytes(ascii);
            for (int i = 0; i + p.Length <= d.Length; i++)
            {
                if (d[i] != p[0]) continue;
                int k = 1;
                while (k < p.Length && d[i + k] == p[k]) k++;
                if (k == p.Length) return true;
            }
            return false;
        }

        static string Hint(string h) { return string.IsNullOrEmpty(h) ? null : h.ToLowerInvariant(); }

        /// <summary>拡張子 (".exe" など) と、表示用の説明を返す。</summary>
        public static string Detect(byte[] d, string hintExt, out string label)
        {
            string hint = Hint(hintExt);
            label = null;
            if (d.Length >= 2 && d[0] == 'M' && d[1] == 'Z')
            {
                label = "Windows 実行ファイル (PE)";
                if (d.Length >= 0x40)
                {
                    int pe = BitConverter.ToInt32(d, 0x3C);
                    if (pe > 0 && pe + 24 + 70 < d.Length && At(d, pe, (byte)'P', (byte)'E', 0, 0))
                    {
                        int chars = BitConverter.ToUInt16(d, pe + 22);
                        int opt = pe + 24;
                        bool pe64 = BitConverter.ToUInt16(d, opt) == 0x20b;
                        int subsystem = BitConverter.ToUInt16(d, opt + 68);
                        int ddir = opt + (pe64 ? 112 : 96);
                        bool dotnet = ddir + 14 * 8 + 8 <= d.Length && BitConverter.ToInt32(d, ddir + 14 * 8) != 0;
                        string kind = (chars & 0x2000) != 0 ? ".dll" : subsystem == 1 ? ".sys" : ".exe";
                        if (kind == ".dll" && (hint == ".ocx" || hint == ".cpl" || hint == ".ax")) kind = hint;
                        if (kind == ".exe" && hint == ".scr") kind = hint;
                        label = (kind == ".dll" ? "DLL" : kind == ".sys" ? "ドライバー" : "実行ファイル") + (pe64 ? " 64bit" : " 32bit") + (dotnet ? " .NET" : "");
                        return kind;
                    }
                }
                return hint == ".com" ? ".com" : ".exe";
            }
            if (At(d, 0, 0x50, 0x4B, 3, 4))
            {
                if (Contains(d, "AndroidManifest.xml")) { label = "Android アプリ"; return ".apk"; }
                if (Contains(d, "META-INF/MANIFEST.MF")) { label = "Java アーカイブ"; return ".jar"; }
                if (Contains(d, "[Content_Types].xml"))
                {
                    if (Contains(d, "word/")) { label = "Word 文書"; return hint == ".docm" ? hint : ".docx"; }
                    if (Contains(d, "xl/")) { label = "Excel"; return hint == ".xlsm" ? hint : ".xlsx"; }
                    if (Contains(d, "ppt/")) { label = "PowerPoint"; return hint == ".pptm" ? hint : ".pptx"; }
                    if (hint == ".msix" || hint == ".appx") { label = "アプリ パッケージ"; return hint; }
                }
                label = "ZIP 圧縮"; return ".zip";
            }
            if (At(d, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))
            {
                label = "OLE 複合ファイル";
                if (hint == ".msi" || hint == ".msp" || hint == ".doc" || hint == ".xls" || hint == ".ppt") return hint;
                return ".msi";
            }
            if (At(d, 0, 0x7F, (byte)'E', (byte)'L', (byte)'F')) { label = "Linux 実行ファイル"; return ".elf"; }
            if (At(d, 0, 0xCA, 0xFE, 0xBA, 0xBE)) { label = "Java クラス"; return ".class"; }
            if (At(d, 0, (byte)'%', (byte)'P', (byte)'D', (byte)'F')) { label = "PDF"; return ".pdf"; }
            if (At(d, 0, (byte)'M', (byte)'S', (byte)'C', (byte)'F')) { label = "CAB 圧縮"; return ".cab"; }
            if (At(d, 0, (byte)'R', (byte)'a', (byte)'r', (byte)'!')) { label = "RAR 圧縮"; return ".rar"; }
            if (At(d, 0, (byte)'7', (byte)'z', 0xBC, 0xAF)) { label = "7z 圧縮"; return ".7z"; }
            if (At(d, 0, 0x4C, 0, 0, 0, 1, 0x14, 2, 0)) { label = "ショートカット"; return ".lnk"; }

            string text = AsText(d);
            if (text == null) { label = "不明なバイナリ"; return hint ?? ".bin"; }
            return DetectText(text, hint, out label);
        }

        /// <summary>テキストとして読めれば文字列を返す。バイナリなら null。</summary>
        public static string AsText(byte[] d)
        {
            if (d.Length == 0) return "";
            string s;
            if (At(d, 0, 0xFF, 0xFE)) s = Encoding.Unicode.GetString(d, 2, d.Length - 2);
            else if (At(d, 0, 0xFE, 0xFF)) s = Encoding.BigEndianUnicode.GetString(d, 2, d.Length - 2);
            else if (At(d, 0, 0xEF, 0xBB, 0xBF)) s = Encoding.UTF8.GetString(d, 3, d.Length - 3);
            else
            {
                int n = Math.Min(d.Length, 65536), zeros = 0, ctrl = 0;
                for (int i = 0; i < n; i++)
                {
                    byte b = d[i];
                    if (b == 0) zeros++;
                    else if (b < 32 && b != 9 && b != 10 && b != 13 && b != 12 && b != 27 && b != 26) ctrl++;
                }
                if (zeros > 0 || ctrl > n / 50) return null;
                try { s = new UTF8Encoding(false, true).GetString(d); }
                catch { try { s = Encoding.GetEncoding(932).GetString(d); } catch { s = Encoding.Default.GetString(d); } }
            }
            return s;
        }

        static int Score(string t, params string[] patterns)
        {
            int n = 0;
            foreach (var p in patterns) if (Regex.IsMatch(t, p, RegexOptions.IgnoreCase | RegexOptions.Multiline)) n++;
            return n;
        }

        public static string DetectText(string t, string hint, out string label)
        {
            string head = t.TrimStart('﻿', ' ', '\t', '\r', '\n');
            string low = head.Length > 400 ? head.Substring(0, 400).ToLowerInvariant() : head.ToLowerInvariant();
            if (low.StartsWith("#!"))
            {
                string first = low.Split('\n')[0];
                label = "スクリプト (" + first.Trim() + ")";
                if (first.Contains("python")) return ".py";
                if (first.Contains("node")) return ".js";
                if (first.Contains("pwsh") || first.Contains("powershell")) return ".ps1";
                if (first.Contains("perl")) return ".pl";
                if (first.Contains("ruby")) return ".rb";
                if (first.Contains("php")) return ".php";
                return ".sh";
            }
            if (low.StartsWith("windows registry editor") || low.StartsWith("regedit4")) { label = "レジストリ"; return ".reg"; }
            if (low.StartsWith("<?php")) { label = "PHP"; return ".php"; }
            if (low.StartsWith("[internetshortcut]")) { label = "インターネット ショートカット"; return ".url"; }
            if (low.Contains("<hta:application") || (hint == ".hta" && low.Contains("<"))) { label = "HTML アプリケーション"; return ".hta"; }
            if (Regex.IsMatch(low, @"^(<\?xml[^>]*>\s*)?<(job|package)[\s>]")) { label = "Windows スクリプト ファイル"; return ".wsf"; }
            if (low.StartsWith("<!doctype html") || low.StartsWith("<html")) { label = "HTML"; return ".html"; }
            if (low.StartsWith("<?xml")) { label = "XML"; return ".xml"; }

            var scores = new Dictionary<string, int>
            {
                { ".bat", Score(t, @"^\s*@?echo\s+(off|on)\b", @"^\s*rem\s", @"^\s*::", @"%~?d?p?0", @"^\s*goto\s+\w", @"^\s*:\w+\s*$", @"^\s*set\s+/?[ap]?\s*\w+=", @"^\s*if\s+(not\s+)?(exist|errorlevel|defined)\b", @"%errorlevel%", @"^\s*(start|call|pause|cls|exit\s+/b)\b") },
                { ".ps1", Score(t, @"\$\w+\s*=", @"\b(Write-Host|Write-Output|Get-\w+|Set-\w+|New-Object|Invoke-\w+|Start-Process)\b", @"^\s*param\s*\(", @"\[CmdletBinding", @"-ErrorAction\b", @"^\s*function\s+[\w-]+\s*(\(|\{)", @"\$env:\w+", @"\|\s*(ForEach-Object|Where-Object|Out-Null|%|\?)\s", @"\[(string|int|System\.[\w.]+)\]") },
                { ".vbs", Score(t, @"^\s*dim\s+\w", @"\bWScript\.(Echo|Sleep|Quit|Arguments|CreateObject|ScriptFullName)", @"\bCreateObject\s*\(", @"^\s*end\s+(sub|function|if)\b", @"^\s*set\s+\w+\s*=\s*", @"\bMsgBox\b", @"^\s*'", @"^\s*on\s+error\s+resume\s+next") },
                { ".js", Score(t, @"\b(var|let|const)\s+\w+\s*=", @"\bfunction\s*\w*\s*\(", @"=>", @"\bconsole\.log\b", @"\bnew\s+ActiveXObject\b", @"\brequire\s*\(", @";\s*$", @"^\s*//") },
                { ".py", Score(t, @"^\s*import\s+\w", @"^\s*from\s+[\w.]+\s+import\b", @"^\s*def\s+\w+\s*\(.*\)\s*:", @"\bprint\s*\(", @"if\s+__name__\s*==", @"^\s*(elif|except|class\s+\w+.*:)", @"^\s*#") },
                { ".sh", Score(t, @"^\s*(fi|done|esac)\s*$", @"^\s*then\s*$", @"\$\{\w+\}", @"^\s*export\s+\w+=", @"\becho\s+""\$") },
            };
            // コメント記号など、どの言語にもありそうな 1 点だけでは決めない
            var best = scores.OrderByDescending(kv => kv.Value).First();
            if (hint != null && scores.ContainsKey(hint) && scores[hint] > 0 && scores[hint] >= best.Value - 1)
            { label = Label(hint); return hint; }
            if (hint == ".cmd" && scores[".bat"] > 0) { label = Label(".bat"); return ".cmd"; }
            if (best.Value >= 2 || (best.Value == 1 && hint == null))
            { label = Label(best.Key); return best.Key; }
            if (hint != null && hint != ".exe" && hint != ".dll") { label = "テキスト"; return hint; }
            string j = head.TrimStart();
            if ((j.StartsWith("{") || j.StartsWith("[")) && (j.TrimEnd().EndsWith("}") || j.TrimEnd().EndsWith("]"))) { label = "JSON"; return ".json"; }
            label = "テキスト";
            return ".txt";
        }

        static string Label(string ext)
        {
            switch (ext)
            {
                case ".bat": case ".cmd": return "バッチファイル";
                case ".ps1": return "PowerShell";
                case ".vbs": return "VBScript";
                case ".js": return "JavaScript";
                case ".py": return "Python";
                case ".sh": return "シェル スクリプト";
                default: return ext.TrimStart('.').ToUpperInvariant();
            }
        }
    }

    public sealed class SaveResult
    {
        public bool Saved;
        public string SavedPath;     // 保存した (または同じ内容がすでにあった) ファイル
        public string Ext, Label;
        public string Message;
    }

    /// <summary>コードを 保存先\名前\名前.拡張子 に保存する。同じ内容は二重に保存しない。</summary>
    public sealed class Saver
    {
        public readonly string Root;
        public long MaxBytes = 200L * 1024 * 1024;
        public readonly List<string> ExcludeDirs = new List<string>();
        public bool Exclude = true;
        readonly Dictionary<string, string> known = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);  // 元ファイル+更新日時 → 保存先
        readonly object gate = new object();

        public Saver(string root) { Root = root; }

        public static string SafeName(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) s = "unknown";
            var sb = new StringBuilder();
            foreach (char c in s) sb.Append(c < 32 || "<>:\"/\\|?*".IndexOf(c) >= 0 ? '_' : c);
            s = sb.ToString();
            s = s.Trim().TrimEnd('.');
            if (s.Length > 80) s = s.Substring(0, 80);
            return s.Length == 0 ? "unknown" : s;
        }

        static bool Under(string path, string dir)
        {
            if (string.IsNullOrEmpty(dir)) return false;
            string d = System.IO.Path.GetFullPath(dir).TrimEnd('\\', '/') + System.IO.Path.DirectorySeparatorChar;
            return System.IO.Path.GetFullPath(path).StartsWith(d, StringComparison.OrdinalIgnoreCase);
        }

        static string Hash(byte[] d) { using (var h = SHA256.Create()) return BitConverter.ToString(h.ComputeHash(d)).Replace("-", ""); }

        static byte[] ReadShared(string path)
        {
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                var buf = new byte[fs.Length];
                int off = 0, r;
                while (off < buf.Length && (r = fs.Read(buf, off, buf.Length - off)) > 0) off += r;
                if (off != buf.Length) Array.Resize(ref buf, off);
                return buf;
            }
        }

        public SaveResult Save(Target t, string cmdLine)
        {
            lock (gate) return SaveLocked(t, cmdLine);
        }

        SaveResult SaveLocked(Target t, string cmdLine)
        {
            if (t.Note != null && t.Note.StartsWith("SKIP:")) return new SaveResult { Message = "対象外: " + t.Note.Substring(5) };
            byte[] data;
            string cacheKey = null;
            if (t.IsInline) data = t.Inline;
            else
            {
                if (string.IsNullOrEmpty(t.Path) || !File.Exists(t.Path)) return new SaveResult { Message = t.Note ?? ("ファイルが見つかりません: " + t.Path) };
                string full = System.IO.Path.GetFullPath(t.Path);
                if (Under(full, Root)) return new SaveResult { Message = "対象外: 保存先の中のファイル" };
                if (Exclude) foreach (var d in ExcludeDirs) if (Under(full, d)) return new SaveResult { Message = "対象外: " + d + " 内" };
                var fi = new FileInfo(full);
                if (fi.Length > MaxBytes) return new SaveResult { Message = "大きすぎるため保存しません (" + (fi.Length >> 20) + " MB)" };
                cacheKey = full + "|" + fi.Length + "|" + fi.LastWriteTimeUtc.Ticks;
                string prev;
                if (known.TryGetValue(cacheKey, out prev) && File.Exists(prev))
                    return new SaveResult { SavedPath = prev, Ext = System.IO.Path.GetExtension(prev), Message = "保存済み" };
                data = ReadShared(full);
            }
            string label;
            string ext = Format.Detect(data, t.HintExt, out label);
            string name = SafeName(t.Name);
            string dir = System.IO.Path.Combine(Root, name);
            Directory.CreateDirectory(dir);
            string hash = Hash(data);
            // 同じフォルダーに同じ内容があれば保存しない
            foreach (var f in Directory.GetFiles(dir))
            {
                if (System.IO.Path.GetFileName(f) == "実行記録.txt") continue;
                var fi = new FileInfo(f);
                if (fi.Length == data.Length && Hash(ReadShared(f)) == hash)
                {
                    if (cacheKey != null) known[cacheKey] = f;
                    Log(dir, t, cmdLine, f, label, false);
                    return new SaveResult { SavedPath = f, Ext = ext, Label = label, Message = "保存済み (同じ内容)" };
                }
            }
            string path = System.IO.Path.Combine(dir, name + ext);
            for (int i = 2; File.Exists(path); i++) path = System.IO.Path.Combine(dir, name + "_" + i + ext);
            File.WriteAllBytes(path, data);
            if (cacheKey != null) known[cacheKey] = path;
            Log(dir, t, cmdLine, path, label, true);
            return new SaveResult { Saved = true, SavedPath = path, Ext = ext, Label = label, Message = "保存しました" };
        }

        static void Log(string dir, Target t, string cmdLine, string saved, string label, bool isNew)
        {
            try
            {
                File.AppendAllText(System.IO.Path.Combine(dir, "実行記録.txt"),
                    DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + (isNew ? "  [新規] " : "  [同じ内容] ") + System.IO.Path.GetFileName(saved) + "  (" + label + ")\r\n" +
                    "    元: " + (t.IsInline ? "コマンドラインに直接書かれたコード (" + t.Host + ")" : t.Path) + "\r\n" +
                    "    コマンドライン: " + cmdLine + "\r\n", new UTF8Encoding(true));
            }
            catch { }
        }
    }
}
