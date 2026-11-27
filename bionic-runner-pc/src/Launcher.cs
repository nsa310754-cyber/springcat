using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace BionicRunnerPC
{
    /// <summary>
    /// Linux / Android 用の実行ファイルを「開く」(エクスプローラーのダブルクリック、画面の「開く」ボタン)。
    /// 画面を出すアプリ (ゲーム等) は WSLg のウィンドウで、そうでないものはコンソールのウィンドウで動かす。
    /// </summary>
    public static class Launcher
    {
        /// <summary>画面を使うアプリが読み込むライブラリ名 (ELF の中の文字列で見分ける)</summary>
        static readonly string[] GuiMarkers =
        {
            "libX11.so", "libxcb.so", "libGL.so", "libGLX", "libEGL.so", "libvulkan.so", "libwayland-client.so",
            "libSDL2", "libSDL3", "libSDL-1.2", "libgtk-3.so", "libgtk-4.so", "libgtk-x11", "libQt5Gui",
            "libQt6Gui", "libglfw", "libsfml-graphics", "libraylib", "libgdk-3.so",
        };

        public sealed class Probe
        {
            public ElfInfo Info;
            public bool Gui;
            public bool AppImage;
            public bool AndroidUi;
        }

        /// <summary>ヘッダの判定に加え、画面を使うか (GUI) どうかを中身の文字列から推定する</summary>
        public static Probe Inspect(Stream s)
        {
            var p = new Probe { Info = ElfInfo.Read(s) };
            if (!p.Info.IsElf()) return p;
            s.Seek(0, SeekOrigin.Begin);
            var head = new byte[16];
            s.Read(head, 0, 16);
            p.AppImage = head[8] == 'A' && head[9] == 'I';
            s.Seek(0, SeekOrigin.Begin);
            var found = Scan(s, 256L << 20, "ANativeActivity_onCreate", GuiMarkers);
            p.AndroidUi = p.Info.kind == ElfInfo.Kind.BionicDynamic && found.Contains("ANativeActivity_onCreate");
            p.Gui = p.AppImage || found.Count > (found.Contains("ANativeActivity_onCreate") ? 1 : 0);
            return p;
        }

        /// <summary>ファイルの先頭 limit バイトに、各文字列が含まれるかを調べる</summary>
        static HashSet<string> Scan(Stream s, long limit, string extra, string[] needles)
        {
            var all = new List<string>(needles) { extra };
            var pats = new List<byte[]>();
            foreach (var n in all) pats.Add(Encoding.ASCII.GetBytes(n));
            var found = new HashSet<string>();
            const int overlap = 64;
            var buf = new byte[1 << 20];
            int keep = 0;
            long read = 0;
            int n2;
            while (read < limit && (n2 = s.Read(buf, keep, buf.Length - keep)) > 0)
            {
                read += n2;
                int len = keep + n2;
                for (int k = 0; k < pats.Count; k++)
                {
                    if (found.Contains(all[k])) continue;
                    if (IndexOf(buf, len, pats[k]) >= 0) found.Add(all[k]);
                }
                if (found.Count == all.Count) break;
                keep = Math.Min(overlap, len);
                Buffer.BlockCopy(buf, len - keep, buf, 0, keep);
            }
            return found;
        }

        static int IndexOf(byte[] hay, int len, byte[] needle)
        {
            byte first = needle[0];
            for (int i = 0; i <= len - needle.Length; i++)
            {
                if (hay[i] != first) continue;
                int j = 1;
                while (j < needle.Length && hay[i + j] == needle[j]) j++;
                if (j == needle.Length) return i;
            }
            return -1;
        }

        /// <summary>
        /// Windows のパスを Linux (WSL) から見えるパスにする。
        /// C:\Games\a → /mnt/c/Games/a、\\wsl.localhost\BionicRunner\opt\x → /opt/x。変換できなければ null
        /// </summary>
        public static string ToLinuxPath(string winPath)
        {
            if (string.IsNullOrEmpty(winPath)) return null;
            if (winPath.Length >= 3 && char.IsLetter(winPath[0]) && winPath[1] == ':' && (winPath[2] == '\\' || winPath[2] == '/'))
                return "/mnt/" + char.ToLowerInvariant(winPath[0]) + "/" + winPath.Substring(3).Replace('\\', '/');
            foreach (var prefix in new[] { @"\\wsl.localhost\", @"\\wsl$\" })
            {
                string p = prefix + Wsl.Distro + @"\";
                if (winPath.StartsWith(p, StringComparison.OrdinalIgnoreCase))
                    return "/" + winPath.Substring(p.Length).Replace('\\', '/');
            }
            return null;
        }

        /// <summary>
        /// Windows 上のファイルを開く。Linux から直接見えない場所 (ネットワーク等) なら Linux 側へ写してから。
        /// 返り値は、起動できなかったときの理由 (起動できたら null)
        /// </summary>
        public static string OpenWindowsFile(string winPath, Probe probe, Action<string> log)
        {
            string linux = ToLinuxPath(winPath);
            if (linux == null)
            {
                string dst = "/tmp/bionic-runner-open/" + Path.GetFileName(winPath);
                Wsl.Result r;
                using (var f = File.OpenRead(winPath))
                    r = Wsl.Sh("mkdir -p /tmp/bionic-runner-open && chmod 1777 /tmp/bionic-runner-open && cat > \"$1\" && chmod 755 \"$1\"",
                               new[] { dst }, f, null, 600000);
                if (!r.Ok) return "Linux へ写せませんでした: " + r.Output;
                linux = dst;
            }
            return OpenLinuxPath(linux, probe, log);
        }

        /// <summary>Linux 側のパスの実行ファイルを開く</summary>
        public static string OpenLinuxPath(string linuxPath, Probe probe, Action<string> log)
        {
            if (probe.AndroidUi)
                return "Android の画面 (NativeActivity) を使うアプリです。Android のアプリの画面は PC では表示できません。";
            string dir = linuxPath.Substring(0, Math.Max(1, linuxPath.LastIndexOf('/')));
            string baseArgs = "-d " + Wsl.Distro + " -u " + Wsl.LinuxUser + " --cd " + Wsl.Quote(dir)
                            + " --exec /usr/local/bin/bropen ";
            if (probe.Gui)
            {
                // 画面のあるアプリ: コンソールは出さず、WSLg のウィンドウだけを出す。失敗したら理由を見せる
                var t = new Thread(() => RunHidden(baseArgs + "--gui " + Wsl.Quote(linuxPath), Path.GetFileName(linuxPath), log))
                { IsBackground = false, Name = "gui-app" };
                t.Start();
                if (log != null) log("画面のあるアプリとして起動しました: " + linuxPath);
                return null;
            }
            // 画面の無いアプリ (コマンド・文字のゲーム等): 本物の端末ウィンドウで動かし、終わったら Enter 待ち
            Process.Start(new ProcessStartInfo(Wsl.Exe, baseArgs + "--pause " + Wsl.Quote(linuxPath)) { UseShellExecute = true });
            if (log != null) log("端末のウィンドウで起動しました: " + linuxPath);
            return null;
        }

        static void RunHidden(string args, string name, Action<string> log)
        {
            var tail = new Queue<string>();
            var r = Wsl.Run(args, null, line =>
            {
                lock (tail)
                {
                    tail.Enqueue(line);
                    while (tail.Count > 25) tail.Dequeue();
                }
            });
            if (r.Ok) return;
            string text;
            lock (tail) text = string.Join("\n", tail.ToArray());
            if (log != null) log(name + " が終了しました (終了コード " + r.Code + ")");
            MessageBox.Show(name + " が終了コード " + r.Code + " で終わりました。\n\n" + text, Installer.AppName,
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        // ------------------------------------------------ エクスプローラーから (--open)

        /// <summary>exe --open "パス" で呼ばれたとき。終わるまで (GUI アプリなら閉じるまで) 戻らない</summary>
        public static void OpenFromExplorer(string path)
        {
            if (!File.Exists(path))
            {
                MessageBox.Show("ファイルが見つかりません:\n" + path, Installer.AppName);
                return;
            }
            Probe probe;
            try
            {
                using (var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    probe = Inspect(f);
            }
            catch (Exception e)
            {
                MessageBox.Show("読み込めませんでした: " + e.Message, Installer.AppName);
                return;
            }
            var k = probe.Info.kind;
            bool runnable = k == ElfInfo.Kind.BionicDynamic || k == ElfInfo.Kind.Static
                         || k == ElfInfo.Kind.ForeignLibc || k == ElfInfo.Kind.Script;
            if (!runnable)
            {
                // 拡張子の無いファイル (README など) もここに来るので、実行ファイルでなければメモ帳で開く
                if (Path.GetExtension(path).Length == 0 && k == ElfInfo.Kind.Unknown)
                {
                    Process.Start("notepad.exe", "\"" + path + "\"");
                    return;
                }
                string why = k == ElfInfo.Kind.SharedObject ? "共有ライブラリ (.so) は単体では実行できません。"
                           : k == ElfInfo.Kind.WindowsPe ? "Windows 用の実行ファイルです。そのまま実行してください。"
                           : k == ElfInfo.Kind.Zip ? "ZIP / APK です。中の実行ファイルは Bionic Runner PC の画面から取り出せます。"
                           : "Linux / Android の実行ファイルではありません。";
                MessageBox.Show(Path.GetFileName(path) + "\n\n" + why, Installer.AppName);
                return;
            }
            if (Wsl.SetupVersion() < 1)
            {
                MessageBox.Show("Linux の準備ができていません。デスクトップの「" + Installer.AppName
                                + "」を開いてセットアップしてください。", Installer.AppName);
                return;
            }
            string err;
            try
            {
                err = OpenWindowsFile(path, probe, null);
            }
            catch (Exception e)
            {
                err = e.Message;
            }
            if (err != null) MessageBox.Show(Path.GetFileName(path) + "\n\n" + err, Installer.AppName);
        }
    }
}
