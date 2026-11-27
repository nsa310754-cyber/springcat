using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace BionicRunnerPC
{
    /// <summary>
    /// 1. WSL 本体を有効にする (管理者の確認あり・必要なら再起動)
    /// 2. Ubuntu 24.04 の rootfs (tar.gz / ISO ではない) を公式サイトから入手して SHA256 を確認
    /// 3. wsl --import で "BionicRunner" ディストリを作る
    /// 4. 中で setup.sh を実行 (glibc の道具・bionic・Termux・qemu を入れる)
    /// UI スレッド以外から呼ぶ。進み具合は Log に流れる。
    /// </summary>
    public sealed class Setup
    {
        public const int CurrentSetupVersion = 4;
        const string UbuntuBase = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/";

        public enum Outcome { Done, NeedReboot, Cancelled, Failed }

        public event Action<string> Log;
        public event Action<string> Step;

        /// <summary>WSL を有効にしてよいか・再起動してよいかを UI に尋ねる</summary>
        public Func<string, bool> Confirm = _ => true;

        void L(string s) { var h = Log; if (h != null) h(s); }
        void S(string s) { var h = Step; if (h != null) h(s); L("» " + s); }

        public Outcome Run()
        {
            try
            {
                return RunInner();
            }
            catch (Exception e)
            {
                L("» セットアップに失敗しました: " + e.Message);
                return Outcome.Failed;
            }
        }

        Outcome RunInner()
        {
            // ---------------------------------------------------------- 1. WSL
            S("WSL を確認しています");
            if (!Wsl.IsInstalled())
            {
                if (!Wsl.ExeExists)
                {
                    L("» この Windows には WSL がありません。Windows 10 (バージョン 2004 以降) か Windows 11 が必要です。");
                    return Outcome.Failed;
                }
                if (!Confirm("Linux を動かすため、Windows の機能「WSL (Windows Subsystem for Linux)」を有効にします。\n\n"
                             + "このあと管理者の確認画面が出るので「はい」を押してください。\n"
                             + "終わったら PC の再起動が必要になることがあります。"))
                    return Outcome.Cancelled;
                S("WSL を有効にしています (別ウィンドウで進みます)");
                int code = InstallWslElevated();
                if (code == int.MinValue) return Outcome.Cancelled;
                L("  WSL のインストーラが終了しました (コード " + code + ")");
                // Windows の機能の有効化は再起動してから効く。再起動後に自動で続きから始める
                Installer.ResumeAfterReboot();
                return Outcome.NeedReboot;
            }
            L("  ✔ WSL は使えます");

            // ------------------------------------------- 2-3. ディストリを作る
            if (!Wsl.DistroExists())
            {
                string tar = DownloadRootfs();
                S("Linux (" + Wsl.Distro + ") を作成しています");
                string dir = Path.Combine(Installer.DataDir, "distro");
                Directory.CreateDirectory(dir);
                var r = Wsl.Run("--import " + Wsl.Distro + " " + Wsl.Quote(dir) + " " + Wsl.Quote(tar) + " --version 2",
                                onLine: line => L("  " + line), timeoutMs: 1800000);
                if (!r.Ok)
                {
                    L("» WSL 2 で作成できませんでした。仮想化 (BIOS/UEFI の VT-x / AMD-V、"
                      + "Windows の「仮想マシン プラットフォーム」) が無効の可能性があります。");
                    if (!Confirm("WSL 2 (仮想マシン方式) が使えませんでした。\n\n"
                                 + "「はい」: WSL の機能を有効化し直す (管理者の確認と再起動が必要)\n"
                                 + "「いいえ」: 互換方式の WSL 1 で作成する (一部のプログラムが動かないことがあります)"))
                    {
                        r = Wsl.Run("--import " + Wsl.Distro + " " + Wsl.Quote(dir) + " " + Wsl.Quote(tar) + " --version 1",
                                    onLine: line => L("  " + line), timeoutMs: 1800000);
                        if (!r.Ok) return Outcome.Failed;
                    }
                    else
                    {
                        if (InstallWslElevated() == int.MinValue) return Outcome.Cancelled;
                        Installer.ResumeAfterReboot();
                        return Outcome.NeedReboot;
                    }
                }
                try { File.Delete(tar); } catch (Exception) { }
                L("  ✔ 作成しました");
            }

            // ------------------------------------------------ 4. Linux 側の準備
            if (Wsl.SetupVersion() < CurrentSetupVersion)
            {
                S("Linux の中身を準備しています (数分かかります)");
                byte[] script;
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("setup.sh"))
                using (var m = new MemoryStream())
                {
                    s.CopyTo(m);
                    // Windows で編集されて CRLF になっていても bash で動くようにする
                    script = new UTF8Encoding(false).GetBytes(
                        new UTF8Encoding(false).GetString(m.ToArray()).Replace("\r\n", "\n"));
                }
                var put = Wsl.Sh("cat > /tmp/bionic-runner-setup.sh", null, new MemoryStream(script));
                if (!put.Ok)
                {
                    L("» Linux を起動できませんでした: " + put.Output);
                    return Outcome.Failed;
                }
                bool done = false;
                var r = Wsl.Exec("/bin/bash /tmp/bionic-runner-setup.sh", null, line =>
                {
                    if (line.StartsWith("@@STEP ", StringComparison.Ordinal)) S(line.Substring(7));
                    else if (line.StartsWith("@@ERROR ", StringComparison.Ordinal)) L("» エラー: " + line.Substring(8));
                    else if (line.StartsWith("@@WARN ", StringComparison.Ordinal)) L("» 注意: " + line.Substring(7));
                    else if (line == "@@DONE") done = true;
                    else L("  " + line);
                }, 3600000);
                if (!r.Ok || !done)
                {
                    L("» Linux 側の準備に失敗しました (終了コード " + r.Code + ")。ネットワークを確認して「セットアップ」をもう一度押してください。");
                    return Outcome.Failed;
                }
                // wsl.conf の変更を反映させるため一度止める
                Wsl.Run("--terminate " + Wsl.Distro, timeoutMs: 60000);
            }

            if (!Associations.OptedOut)
            {
                try
                {
                    Associations.Register();
                    L("  ✔ エクスプローラーに Linux / Android の実行ファイルを登録しました (ダブルクリックで実行できます)");
                }
                catch (Exception e)
                {
                    L("  ✖ エクスプローラーへの登録に失敗しました: " + e.Message);
                }
            }
            if (Installer.AddTerminalShortcuts())
                L("  ✔ デスクトップに「Linux (glibc)」「Termux (bionic)」のアイコンを追加しました");
            else
                L("  ✖ デスクトップのアイコンを作成できませんでした (「セットアップ ▾」から作り直せます)");
            return Outcome.Done;
        }

        /// <summary>管理者として WSL を入れる。キャンセルされたら int.MinValue</summary>
        static int InstallWslElevated()
        {
            // 新しい Windows は wsl --install --no-distribution (ディストリは入れない)。
            // 古い Windows 10 はこのオプションが無いので DISM で機能を有効にしてカーネルを更新する。
            string wsl = Wsl.Exe;
            string cmd = "/c \"\"" + wsl + "\" --install --no-distribution"
                       + " || (dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart"
                       + " & dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart"
                       + " & \"" + wsl + "\" --update)\"";
            var psi = new ProcessStartInfo("cmd.exe", cmd);
            psi.Verb = "runas";
            psi.UseShellExecute = true;
            try
            {
                using (var p = Process.Start(psi))
                {
                    p.WaitForExit();
                    return p.ExitCode;
                }
            }
            catch (System.ComponentModel.Win32Exception)
            {
                return int.MinValue; // 管理者の確認で「いいえ」
            }
        }

        // -------------------------------------------------- rootfs のダウンロード

        string DownloadRootfs()
        {
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | (SecurityProtocolType)12288; // TLS 1.2 / 1.3
            string arch = Wsl.HostArch() == "aarch64" ? "arm64" : "amd64";
            S("Ubuntu 24.04 (" + arch + ") の rootfs を探しています");

            string listing = GetString(UbuntuBase);
            string file = null;
            Version best = null;
            foreach (Match m in Regex.Matches(listing, @"ubuntu-base-(24\.04(?:\.\d+)?)-base-" + arch + @"\.tar\.gz"))
            {
                var v = new Version(m.Groups[1].Value.Split('.').Length == 2 ? m.Groups[1].Value + ".0" : m.Groups[1].Value);
                if (best == null || v > best) { best = v; file = m.Value; }
            }
            if (file == null) throw new Exception("Ubuntu の rootfs が見つかりませんでした (" + UbuntuBase + ")");

            string sums = GetString(UbuntuBase + "SHA256SUMS");
            string want = null;
            foreach (var line in sums.Split('\n'))
            {
                var parts = line.Trim().Split(new[] { ' ' }, 2, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length == 2 && parts[1].TrimStart('*') == file) want = parts[0].ToLowerInvariant();
            }
            if (want == null) throw new Exception("チェックサムが見つかりませんでした: " + file);

            string cache = Path.Combine(Installer.DataDir, "cache");
            Directory.CreateDirectory(cache);
            string gz = Path.Combine(cache, file);
            if (!File.Exists(gz) || Sha256(gz) != want)
            {
                S("ダウンロード中: " + file);
                Download(UbuntuBase + file, gz);
                if (Sha256(gz) != want)
                {
                    File.Delete(gz);
                    throw new Exception("ダウンロードしたファイルが壊れています (SHA256 不一致)。もう一度試してください。");
                }
            }
            L("  ✔ SHA256 を確認しました: " + want.Substring(0, 16) + "…");

            // wsl --import は古い版だと .tar.gz を受け付けないので、展開した .tar を渡す
            string tar = Path.Combine(cache, Path.GetFileNameWithoutExtension(file));
            using (var i = new GZipStream(File.OpenRead(gz), CompressionMode.Decompress))
            using (var o = File.Create(tar))
                i.CopyTo(o);
            try { File.Delete(gz); } catch (Exception) { }
            return tar;
        }

        static string GetString(string url)
        {
            using (var wc = new WebClient())
            {
                wc.Encoding = Encoding.UTF8;
                wc.Headers[HttpRequestHeader.UserAgent] = "BionicRunnerPC/1.0";
                return wc.DownloadString(url);
            }
        }

        void Download(string url, string dst)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = "BionicRunnerPC/1.0";
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var s = resp.GetResponseStream())
            using (var f = File.Create(dst + ".part"))
            {
                long total = resp.ContentLength, got = 0;
                int lastPct = -1;
                var buf = new byte[1 << 16];
                int n;
                while ((n = s.Read(buf, 0, buf.Length)) > 0)
                {
                    f.Write(buf, 0, n);
                    got += n;
                    int pct = total > 0 ? (int)(got * 100 / total) : -1;
                    if (pct >= 0 && pct / 10 != lastPct / 10)
                    {
                        lastPct = pct;
                        L("  " + pct + "% (" + (got >> 20) + " / " + (total >> 20) + " MB)");
                    }
                }
            }
            if (File.Exists(dst)) File.Delete(dst);
            File.Move(dst + ".part", dst);
        }

        static string Sha256(string path)
        {
            using (var sha = SHA256.Create())
            using (var f = File.OpenRead(path))
            {
                var sb = new StringBuilder();
                foreach (var b in sha.ComputeHash(f)) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }
    }
}
