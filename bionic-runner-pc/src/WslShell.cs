using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;

namespace BionicRunnerPC
{
    /// <summary>
    /// 常駐するシェル 1 本 (WSL の BionicRunner ディストリの bash。管理者なら root、普段は一般ユーザー)。
    /// 入力した行はそのままシェルの標準入力へ流すので、実行中のプログラムへの入力にもなる。
    /// Android 版の RootShell.java と同じ作り (疑似端末ではないので vi などの全画面プログラムは動かない。
    /// それらは「Linux ターミナル」ボタンで開く本物の端末で使う)。
    /// </summary>
    public sealed class WslShell
    {
        const string PidMark = "@@BIONIC_RUNNER_PID:";

        public event Action<string> Output;  // 別スレッドから呼ばれる
        public event Action<int> Exited;     // 別スレッドから呼ばれる

        Process process;
        Stream stdin;
        volatile int shellPid = -1;
        volatile bool closed;

        public bool IsAlive
        {
            get { return process != null && !closed; }
        }

        /// <summary>wsl.exe の起動を待つのでUI スレッド以外から呼ぶこと</summary>
        public void Start(string user, string initScript)
        {
            // stderr も同じ流れにまとめて、出力の順番が入れ替わらないようにする
            var psi = Wsl.StartInfo("-d " + Wsl.Distro + " -u " + user + " --cd ~ --exec /bin/bash -c "
                                    + Wsl.Quote("exec 2>&1; exec /bin/bash --login"));
            process = Process.Start(psi);
            stdin = process.StandardInput.BaseStream;
            var proc = process;

            var t = new Thread(() => ReadLoop(proc)) { IsBackground = true, Name = "shell-reader" };
            t.Start();
            var e = new Thread(() =>
            {
                // --exec で動かすので普通は空。wsl.exe 自体のエラーだけがここに来る
                var dec = new LineSplitter(line => Emit(line + "\n"));
                var buf = new byte[4096];
                int n;
                try
                {
                    while ((n = proc.StandardError.BaseStream.Read(buf, 0, buf.Length)) > 0) dec.Feed(buf, n);
                }
                catch (IOException) { }
                dec.Flush();
            }) { IsBackground = true, Name = "shell-stderr" };
            e.Start();

            Send("echo \"" + PidMark + "$$\"");
            if (!string.IsNullOrEmpty(initScript)) Send(initScript);
        }

        void Emit(string s)
        {
            var h = Output;
            if (h != null) h(s);
        }

        void ReadLoop(Process proc)
        {
            var head = new StringBuilder(); // PID 行を取り出すまでの溜め
            var dec = new UTF8Encoding(false).GetDecoder();
            var buf = new byte[8192];
            var cbuf = new char[8200];
            try
            {
                int n;
                while ((n = proc.StandardOutput.BaseStream.Read(buf, 0, buf.Length)) > 0)
                {
                    int c = dec.GetChars(buf, 0, n, cbuf, 0);
                    string s = new string(cbuf, 0, c);
                    if (shellPid < 0)
                    {
                        head.Append(s);
                        string hs = head.ToString();
                        int at = hs.IndexOf(PidMark, StringComparison.Ordinal);
                        int nl = at < 0 ? -1 : hs.IndexOf('\n', at);
                        if (nl < 0)
                        {
                            if (head.Length < 4096) continue;
                            shellPid = 0; // 目印が来ない: 諦めてそのまま流す
                            s = hs;
                        }
                        else
                        {
                            int pid;
                            shellPid = int.TryParse(hs.Substring(at + PidMark.Length, nl - at - PidMark.Length).Trim(),
                                                    out pid) ? pid : 0;
                            s = hs.Substring(0, at) + hs.Substring(nl + 1);
                        }
                        if (s.Length == 0) continue;
                    }
                    Emit(s);
                }
            }
            catch (IOException) { } // シェル終了時に来る
            catch (ObjectDisposedException) { }

            int code = -1;
            try
            {
                proc.WaitForExit();
                code = proc.ExitCode;
            }
            catch (Exception) { }
            if (!closed)
            {
                closed = true;
                var h = Exited;
                if (h != null) h(code);
            }
        }

        /// <summary>1 行送る。失敗 (シェルが死んでいる) なら false</summary>
        public bool Send(string line)
        {
            lock (this)
            {
                if (stdin == null || closed) return false;
                try
                {
                    var b = new UTF8Encoding(false).GetBytes(line + "\n");
                    stdin.Write(b, 0, b.Length);
                    stdin.Flush();
                    return true;
                }
                catch (Exception)
                {
                    return false;
                }
            }
        }

        /// <summary>実行中の子プロセスに SIGINT を送る (Ctrl-C 相当)。シェル自身は残す</summary>
        public void Interrupt()
        {
            int pid = shellPid;
            if (pid <= 0) return;
            // SIGINT を無視するプログラムもあるので、止まらなければ少し待って SIGTERM を送る
            string script = "pkill -INT -P " + pid + "; sleep 1.5; pgrep -P " + pid + " >/dev/null && pkill -TERM -P " + pid;
            ThreadPool.QueueUserWorkItem(_ => Wsl.Exec("/bin/sh -c " + Wsl.Quote(script), timeoutMs: 15000));
        }

        public void Close()
        {
            closed = true;
            int pid = shellPid;
            var p = process;
            if (p == null) return;
            try { if (stdin != null) stdin.Close(); } catch (Exception) { }
            ThreadPool.QueueUserWorkItem(_ =>
            {
                if (pid > 0)
                    Wsl.Exec("/bin/sh -c " + Wsl.Quote("pkill -KILL -P " + pid + "; kill -KILL " + pid),
                             timeoutMs: 15000);
                try { if (!p.HasExited) p.Kill(); } catch (Exception) { }
            });
        }
    }
}
