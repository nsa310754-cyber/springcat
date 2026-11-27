using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using Microsoft.Win32;

namespace BionicRunnerPC
{
    /// <summary>
    /// exe を %LOCALAPPDATA%\Programs\BionicRunnerPC に置き、デスクトップ (ホーム画面) と
    /// スタートメニューにアイコンを追加する。管理者権限は使わない (自分のユーザーの範囲だけ)。
    /// </summary>
    public static class Installer
    {
        public const string AppName = "Bionic Runner PC";
        const string ShortcutMain = "Bionic Runner PC";
        const string ShortcutLinux = "Linux (glibc)";
        const string ShortcutTermux = "Termux (bionic)";

        public static string DataDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BionicRunnerPC"); }
        }

        public static string InstallDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "BionicRunnerPC"); }
        }

        public static string CurrentExe
        {
            get { return Assembly.GetExecutingAssembly().Location; }
        }

        public static string InstalledExe
        {
            get { return Path.Combine(InstallDir, "BionicRunnerPC.exe"); }
        }

        static string StartMenuDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), AppName); }
        }

        static string Desktop
        {
            get { return Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory); }
        }

        /// <summary>
        /// 起動のたびに呼ぶ。インストール先に最新の exe とアイコンを置き、メインのアイコンが無ければ作る。
        /// 戻り値は新しくアイコンを作ったかどうか。
        /// </summary>
        public static bool EnsureInstalled()
        {
            Directory.CreateDirectory(InstallDir);
            string self = Assembly.GetExecutingAssembly().Location;
            if (!string.Equals(Path.GetFullPath(self), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    File.Copy(self, InstalledExe, true);
                }
                catch (IOException)
                {
                    // インストール済みの方が実行中で上書きできない: そのまま使う
                }
            }
            foreach (var name in new[] { "app.ico", "linux.ico", "termux.ico" }) ExtractIcon(name);

            string desktopLnk = Path.Combine(Desktop, ShortcutMain + ".lnk");
            bool existed = File.Exists(desktopLnk);
            bool created = MakeShortcut(desktopLnk, InstalledExe, "",
                                        "Linux (glibc / bionic) の実行環境", Icon("app.ico"), InstallDir) && !existed;
            Directory.CreateDirectory(StartMenuDir);
            MakeShortcut(Path.Combine(StartMenuDir, ShortcutMain + ".lnk"), InstalledExe, "",
                         "Linux (glibc / bionic) の実行環境", Icon("app.ico"), InstallDir);
            return created;
        }

        /// <summary>
        /// セットアップ完了後に、Linux と Termux の端末を開くアイコンを追加する。作れたら true。
        /// アイコンの先はこの exe (--terminal)。普通に開けば一般ユーザー ($)、
        /// 右クリック →「管理者として実行」なら root (#) の端末になる。
        /// </summary>
        public static bool AddTerminalShortcuts()
        {
            bool ok = true;
            string exe = InstalledExe;
            string linuxArgs = "--terminal linux";
            string termuxArgs = "--terminal termux";
            Directory.CreateDirectory(StartMenuDir);
            foreach (var dir in new[] { Desktop, StartMenuDir })
            {
                ok &= MakeShortcut(Path.Combine(dir, ShortcutLinux + ".lnk"), exe, linuxArgs,
                                   "Ubuntu (glibc) の端末を開く (管理者として実行すると root)", Icon("linux.ico"), InstallDir);
                ok &= MakeShortcut(Path.Combine(dir, ShortcutTermux + ".lnk"), exe, termuxArgs,
                                   "Termux (Android の bionic) の端末を開く (管理者として実行すると root)", Icon("termux.ico"), InstallDir);
            }
            return ok;
        }

        static string Icon(string name)
        {
            return Path.Combine(InstallDir, name);
        }

        static void ExtractIcon(string name)
        {
            string dst = Icon(name);
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (s == null) return;
                if (File.Exists(dst) && new FileInfo(dst).Length == s.Length) return;
                try
                {
                    using (var f = File.Create(dst)) s.CopyTo(f);
                }
                catch (IOException) { }
            }
        }

        /// <summary>.lnk を作る (あれば作り直す)。保存できたら true</summary>
        static bool MakeShortcut(string lnk, string target, string args, string description,
                                 string icon, string workDir)
        {
            try
            {
                // Windows のシェルの IShellLink を直接使う (WScript.Shell は会社 PC などで無効化されていることがある)
                var link = (IShellLinkW)new ShellLink();
                link.SetPath(target);
                link.SetArguments(args);
                link.SetDescription(description);
                if (File.Exists(icon)) link.SetIconLocation(icon, 0);
                if (workDir != null) link.SetWorkingDirectory(workDir);
                ((IPersistFile)link).Save(lnk, true);
                Marshal.FinalReleaseComObject(link);
                return File.Exists(lnk);
            }
            catch (Exception)
            {
                return false; // アイコンが作れなくてもアプリ自体は使える
            }
        }

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        class ShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
        interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cch, IntPtr pfd, int fFlags);
            void GetIDList(out IntPtr ppidl);
            void SetIDList(IntPtr pidl);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cch);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cch);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cch);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
            void GetHotkey(out short pwHotkey);
            void SetHotkey(short wHotkey);
            void GetShowCmd(out int piShowCmd);
            void SetShowCmd(int iShowCmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cch, out int piIcon);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, int dwReserved);
            void Resolve(IntPtr hwnd, int fFlags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
        }

        // ------------------------------------------------ 再起動後に続きから始める

        const string RunOnceKey = @"Software\Microsoft\Windows\CurrentVersion\RunOnce";

        public static void ResumeAfterReboot()
        {
            using (var k = Registry.CurrentUser.CreateSubKey(RunOnceKey))
            {
                if (k != null) k.SetValue("BionicRunnerPC", "\"" + InstalledExe + "\" --continue-setup");
            }
        }

        // -------------------------------------------------------- アンインストール

        public static void Uninstall()
        {
            try { Associations.Unregister(); } catch (Exception) { }
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\BionicRunnerPC", false); } catch (Exception) { }
            Wsl.Run("--unregister " + Wsl.Distro, timeoutMs: 300000);
            foreach (var dir in new[] { Desktop, StartMenuDir })
                foreach (var n in new[] { ShortcutMain, ShortcutLinux, ShortcutTermux })
                {
                    try { File.Delete(Path.Combine(dir, n + ".lnk")); } catch (Exception) { }
                }
            try { Directory.Delete(StartMenuDir, true); } catch (Exception) { }
            try { Directory.Delete(DataDir, true); } catch (Exception) { }
            using (var k = Registry.CurrentUser.OpenSubKey(RunOnceKey, true))
            {
                if (k != null) k.DeleteValue("BionicRunnerPC", false);
            }
            // 実行中の exe は自分では消せないので、終了後に cmd に消してもらう
            var psi = new ProcessStartInfo("cmd.exe",
                "/c ping 127.0.0.1 -n 3 >nul & rmdir /s /q \"" + InstallDir + "\"");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            Process.Start(psi);
        }
    }
}
