using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace BionicRunnerPC
{
    /// <summary>wsl.exe の呼び出しをまとめたもの。</summary>
    public static class Wsl
    {
        public const string Distro = "BionicRunner";
        public const string Root = "/opt/bionic-runner";
        public const string BinDir = Root + "/bin";

        /// <summary>wsl.exe のフルパス。32bit プロセスから呼んでも 64bit 版を使う</summary>
        public static string Exe
        {
            get
            {
                string win = Environment.GetEnvironmentVariable("SystemRoot") ?? @"C:\Windows";
                string sysnative = Path.Combine(win, "Sysnative", "wsl.exe");
                if (!Environment.Is64BitProcess && File.Exists(sysnative)) return sysnative;
                return Path.Combine(win, "System32", "wsl.exe");
            }
        }

        public static bool ExeExists
        {
            get { return File.Exists(Exe); }
        }

        // ------------------------------------------------ Windows の権限 ↔ Linux のユーザー

        static bool? isAdmin;

        /// <summary>このアプリが Windows の管理者として (昇格して) 実行されているか</summary>
        public static bool IsAdmin
        {
            get
            {
                if (isAdmin == null)
                {
                    // --user: 管理者として実行されていても一般ユーザー ($) で開く
                    if (Array.IndexOf(Environment.GetCommandLineArgs(), "--user") >= 0)
                        isAdmin = false;
                    else
                    try
                    {
                        isAdmin = new System.Security.Principal.WindowsPrincipal(
                                System.Security.Principal.WindowsIdentity.GetCurrent())
                            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
                    }
                    catch (Exception)
                    {
                        isAdmin = false;
                    }
                }
                return isAdmin.Value;
            }
        }

        /// <summary>管理者なら Linux でも root (#)、そうでなければ一般ユーザー user ($)</summary>
        public static string LinuxUser
        {
            get { return IsAdmin ? "root" : "user"; }
        }

        public static string Prompt
        {
            get { return IsAdmin ? "#" : "$"; }
        }

        /// <summary>Linux / Termux の端末を開く wsl.exe の引数 (権限に合わせて root か一般ユーザー)</summary>
        public static string TerminalArgs(bool termux)
        {
            if (termux)
                return "-d " + Distro + " -u " + LinuxUser + " --exec /usr/local/bin/termux" + (IsAdmin ? " --root" : "");
            return "-d " + Distro + " -u " + LinuxUser + " --cd ~";
        }

        /// <summary>端末 (コンソール) を開く。開けなければ例外</summary>
        public static void OpenTerminal(bool termux)
        {
            Process.Start(new ProcessStartInfo(Exe, TerminalArgs(termux)) { UseShellExecute = true });
        }

        public sealed class Result
        {
            public int Code;
            public string Output = "";
            public bool Ok { get { return Code == 0; } }
        }

        public static ProcessStartInfo StartInfo(string args)
        {
            var psi = new ProcessStartInfo(Exe, args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardInput = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            // wsl.exe 自身のメッセージを UTF-16 ではなく UTF-8 で出させる
            psi.EnvironmentVariables["WSL_UTF8"] = "1";
            return psi;
        }

        /// <summary>
        /// wsl.exe を実行して終わるまで待つ。stdin を渡せば標準入力に流し込む。
        /// onLine を渡すと出力を 1 行ずつ (別スレッドから) 受け取れる。
        /// </summary>
        public static Result Run(string args, Stream stdin = null, Action<string> onLine = null,
                                 int timeoutMs = Timeout.Infinite)
        {
            var res = new Result();
            Process p;
            try
            {
                p = Process.Start(StartInfo(args));
            }
            catch (Exception e)
            {
                res.Code = -1;
                res.Output = e.Message;
                return res;
            }
            using (p)
            {
                var all = new StringBuilder();
                object gate = new object();
                Action<Stream> pump = s =>
                {
                    var lines = new LineSplitter(line =>
                    {
                        lock (gate) all.Append(line).Append('\n');
                        if (onLine != null) onLine(line);
                    });
                    var buf = new byte[16384];
                    int n;
                    try
                    {
                        while ((n = s.Read(buf, 0, buf.Length)) > 0) lines.Feed(buf, n);
                    }
                    catch (IOException) { }
                    lines.Flush();
                };
                var t1 = new Thread(() => pump(p.StandardOutput.BaseStream)) { IsBackground = true };
                var t2 = new Thread(() => pump(p.StandardError.BaseStream)) { IsBackground = true };
                t1.Start();
                t2.Start();
                try
                {
                    if (stdin != null) stdin.CopyTo(p.StandardInput.BaseStream);
                    p.StandardInput.Close();
                }
                catch (IOException) { }
                if (!p.WaitForExit(timeoutMs))
                {
                    try { p.Kill(); } catch (Exception) { }
                    res.Code = -2;
                }
                else
                {
                    t1.Join(5000);
                    t2.Join(5000);
                    res.Code = p.ExitCode;
                }
                lock (gate) res.Output = all.ToString().Trim();
            }
            return res;
        }

        /// <summary>ディストリの中でコマンドを root で実行する (シェルを通さず直接 exec)</summary>
        public static Result Exec(string commandLine, Stream stdin = null, Action<string> onLine = null,
                                  int timeoutMs = 120000)
        {
            return Run("-d " + Distro + " -u root --exec " + commandLine, stdin, onLine, timeoutMs);
        }

        /// <summary>sh -c で実行する。script 内の $1 $2 … に args が入る</summary>
        public static Result Sh(string script, IList<string> args = null, Stream stdin = null,
                                Action<string> onLine = null, int timeoutMs = 120000)
        {
            var sb = new StringBuilder("/bin/sh -c ");
            sb.Append(Quote(script)).Append(" sh");
            if (args != null) foreach (var a in args) sb.Append(' ').Append(Quote(a));
            return Exec(sb.ToString(), stdin, onLine, timeoutMs);
        }

        /// <summary>
        /// wsl.exe へ渡す 1 引数として囲む (Windows のコマンドライン規則)。
        /// --exec の後ろは Linux 側へそのまま argv として渡る。
        /// </summary>
        public static string Quote(string s)
        {
            if (s.Length > 0 && s.IndexOfAny(new[] { ' ', '\t', '"', '\n' }) < 0) return s;
            var sb = new StringBuilder("\"");
            int bs = 0;
            foreach (char c in s)
            {
                if (c == '\\') { bs++; continue; }
                if (c == '"') { sb.Append('\\', bs * 2 + 1); sb.Append('"'); bs = 0; continue; }
                sb.Append('\\', bs);
                bs = 0;
                sb.Append(c);
            }
            sb.Append('\\', bs * 2);
            sb.Append('"');
            return sb.ToString();
        }

        /// <summary>シェルの単一引用符で安全に囲む</summary>
        public static string Sq(string s)
        {
            return "'" + s.Replace("'", "'\\''") + "'";
        }

        // ------------------------------------------------------------ 状態の確認

        /// <summary>WSL 本体が使える状態か (未インストールなら false)</summary>
        public static bool IsInstalled()
        {
            if (!ExeExists) return false;
            if (Run("--version", timeoutMs: 60000).Ok) return true;   // ストア版 WSL
            return Run("--status", timeoutMs: 60000).Ok;              // Windows 同梱版 WSL
        }

        public static List<string> ListDistros()
        {
            var list = new List<string>();
            var r = Run("--list --quiet", timeoutMs: 60000);
            if (!r.Ok) return list;
            foreach (var raw in r.Output.Split('\n'))
            {
                // 古い wsl.exe は UTF-16 で出力する (BOM や NUL が混ざる) ので取り除く
                string s = raw.Replace("\0", "").Trim('\uFEFF', '\uFFFD', ' ', '\t', '\r');
                if (s.Length > 0) list.Add(s);
            }
            return list;
        }

        public static bool DistroExists()
        {
            foreach (var d in ListDistros())
                if (string.Equals(d, Distro, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        public static int SetupVersion()
        {
            var r = Exec("/bin/cat " + Root + "/.setup-version", timeoutMs: 120000);
            int v;
            return r.Ok && int.TryParse(r.Output.Trim(), out v) ? v : 0;
        }

        /// <summary>
        /// Linux 側のファイルの中身を読む。まず \\wsl.localhost を試し、だめなら wsl 経由で cat する。
        /// 大きすぎる (maxBytes 超) ときは先頭だけ。
        /// </summary>
        public static byte[] ReadFile(string linuxPath, int maxBytes = 64 << 20)
        {
            try
            {
                using (var f = new FileStream(UncPath(linuxPath), FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    return ReadAll(f, maxBytes);
            }
            catch (Exception) { }
            var psi = StartInfo("-d " + Distro + " -u root --exec /bin/cat " + Quote(linuxPath));
            using (var p = Process.Start(psi))
            {
                p.StandardInput.Close();
                ThreadPool.QueueUserWorkItem(_ => { try { p.StandardError.ReadToEnd(); } catch (Exception) { } });
                var data = ReadAll(p.StandardOutput.BaseStream, maxBytes);
                if (!p.WaitForExit(30000)) { try { p.Kill(); } catch (Exception) { } }
                return data;
            }
        }

        static byte[] ReadAll(Stream s, int maxBytes)
        {
            var m = new MemoryStream();
            var buf = new byte[65536];
            int n;
            while (m.Length < maxBytes && (n = s.Read(buf, 0, (int)Math.Min(buf.Length, maxBytes - m.Length))) > 0)
                m.Write(buf, 0, n);
            return m.ToArray();
        }

        /// <summary>Linux 側のパスを Windows から開ける UNC パスにする</summary>
        public static string UncPath(string linuxPath)
        {
            string rel = linuxPath.TrimStart('/').Replace('/', '\\');
            string a = @"\\wsl.localhost\" + Distro + @"\" + rel;
            try
            {
                if (File.Exists(a) || Directory.Exists(a)) return a;
            }
            catch (Exception) { }
            return @"\\wsl$\" + Distro + @"\" + rel;
        }

        // --------------------------------------------------------------- CPU

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool IsWow64Process2(IntPtr process, out ushort processMachine, out ushort nativeMachine);

        [DllImport("kernel32.dll")]
        static extern IntPtr GetCurrentProcess();

        /// <summary>この PC の CPU: "x86_64" か "aarch64"</summary>
        public static string HostArch()
        {
            try
            {
                ushort pm, nm;
                if (IsWow64Process2(GetCurrentProcess(), out pm, out nm))
                {
                    if (nm == 0xAA64) return "aarch64";
                    if (nm == 0x8664) return "x86_64";
                }
            }
            catch (Exception) { } // Windows 10 1709 より前には無い
            string a = (Environment.GetEnvironmentVariable("PROCESSOR_ARCHITEW6432")
                        ?? Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") ?? "").ToUpperInvariant();
            return a == "ARM64" ? "aarch64" : "x86_64";
        }
    }

    /// <summary>バイト列を UTF-8 の行に区切る (マルチバイト文字が境目で割れても壊れない)</summary>
    public sealed class LineSplitter
    {
        readonly Decoder dec = new UTF8Encoding(false).GetDecoder();
        readonly StringBuilder cur = new StringBuilder();
        readonly Action<string> onLine;
        char[] cbuf = new char[16384];

        public LineSplitter(Action<string> onLine) { this.onLine = onLine; }

        public void Feed(byte[] buf, int n)
        {
            if (cbuf.Length < n + 4) cbuf = new char[n + 4];
            int c = dec.GetChars(buf, 0, n, cbuf, 0);
            for (int i = 0; i < c; i++)
            {
                char ch = cbuf[i];
                if (ch == '\0') continue; // 古い wsl.exe の UTF-16 出力の名残
                if (ch == '\n') { Emit(); continue; }
                cur.Append(ch);
            }
        }

        void Emit()
        {
            string s = cur.ToString().TrimEnd('\r');
            cur.Length = 0;
            // 進捗表示の "\r" 上書きは最後の状態だけ残す
            int cr = s.LastIndexOf('\r');
            if (cr >= 0) s = s.Substring(cr + 1);
            onLine(s);
        }

        public void Flush()
        {
            if (cur.Length > 0) Emit();
        }
    }
}
