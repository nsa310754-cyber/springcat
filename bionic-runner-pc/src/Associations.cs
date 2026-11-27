using System;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace BionicRunnerPC
{
    /// <summary>
    /// Windows (エクスプローラー) に Linux / Android の実行ファイルを覚えさせる。
    /// 自分のユーザーの設定 (HKCU) だけを変更するので管理者権限は要らない。
    ///   ・拡張子なしのファイル (Linux の実行ファイルは普通これ) と .AppImage .x86_64 などをダブルクリック → 実行
    ///     (実行ファイルでない拡張子なしのファイル、例えば README はメモ帳で開く)
    ///   ・右クリック →「管理者として実行」→ root で実行
    ///   ・どのファイルでも右クリック →「Linux で実行 (Bionic Runner)」
    /// </summary>
    public static class Associations
    {
        const string ProgId = "BionicRunnerPC.LinuxExecutable";
        const string ContextVerb = "BionicRunnerPC";
        const string Classes = @"Software\Classes\";
        const string SettingsKey = @"Software\BionicRunnerPC";

        /// <summary>Linux の実行ファイルによくある拡張子 ("." は拡張子なし)</summary>
        static readonly string[] Extensions = { ".", ".appimage", ".x86_64", ".x86", ".elf", ".arm64", ".aarch64", ".run" };

        /// <summary>利用者が「登録を解除」したか (なら起動時に自動で登録し直さない)</summary>
        public static bool OptedOut
        {
            get
            {
                using (var k = Registry.CurrentUser.OpenSubKey(SettingsKey))
                    return k != null && (k.GetValue("NoExplorer") as int? ?? 0) == 1;
            }
            set
            {
                using (var k = Registry.CurrentUser.CreateSubKey(SettingsKey))
                    if (k != null) k.SetValue("NoExplorer", value ? 1 : 0, RegistryValueKind.DWord);
            }
        }

        public static bool IsRegistered
        {
            get
            {
                using (var k = Registry.CurrentUser.OpenSubKey(Classes + ProgId + @"\shell\open\command"))
                    return k != null && ((k.GetValue("") as string) ?? "").Contains(Installer.InstalledExe);
            }
        }

        public static void Register()
        {
            string exe = Installer.InstalledExe;
            string open = "\"" + exe + "\" --open \"%1\"";
            string icon = Path.Combine(Installer.InstallDir, "linux.ico");

            using (var k = Registry.CurrentUser.CreateSubKey(Classes + ProgId))
            {
                k.SetValue("", "Linux / Android の実行ファイル");
                using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", icon);
                using (var s = k.CreateSubKey(@"shell"))
                    s.SetValue("", "open");
                using (var o = k.CreateSubKey(@"shell\open"))
                {
                    o.SetValue("", "Linux で実行");
                    using (var c = o.CreateSubKey("command")) c.SetValue("", open);
                }
                // 「管理者として実行」(盾のマーク付き) → 昇格した exe → Linux でも root
                using (var r = k.CreateSubKey(@"shell\runas"))
                {
                    r.SetValue("", "管理者として実行 (Linux の root)");
                    r.SetValue("HasLUAShield", "");
                    using (var c = r.CreateSubKey("command")) c.SetValue("", open);
                }
            }

            foreach (var ext in Extensions)
            {
                using (var k = Registry.CurrentUser.CreateSubKey(Classes + ext))
                {
                    // 他のアプリが既に使っている拡張子は横取りしない (「プログラムから開く」の候補に加えるだけ)
                    string cur = k.GetValue("") as string;
                    if (string.IsNullOrEmpty(cur) || cur == ProgId) k.SetValue("", ProgId);
                    using (var w = k.CreateSubKey("OpenWithProgids")) w.SetValue(ProgId, "");
                }
            }

            // どのファイルにも出る右クリックのメニュー (Windows 11 では「その他のオプションを確認」の中)
            using (var k = Registry.CurrentUser.CreateSubKey(Classes + @"*\shell\" + ContextVerb))
            {
                k.SetValue("", "Linux で実行 (Bionic Runner)");
                k.SetValue("Icon", icon);
                using (var c = k.CreateSubKey("command")) c.SetValue("", open);
            }
            Notify();
        }

        public static void Unregister()
        {
            foreach (var ext in Extensions)
            {
                using (var k = Registry.CurrentUser.OpenSubKey(Classes + ext, true))
                {
                    if (k == null) continue;
                    if ((k.GetValue("") as string) == ProgId) k.DeleteValue("", false);
                    using (var w = k.OpenSubKey("OpenWithProgids", true))
                        if (w != null) w.DeleteValue(ProgId, false);
                }
            }
            try { Registry.CurrentUser.DeleteSubKeyTree(Classes + @"*\shell\" + ContextVerb, false); } catch (Exception) { }
            try { Registry.CurrentUser.DeleteSubKeyTree(Classes + ProgId, false); } catch (Exception) { }
            Notify();
        }

        [DllImport("shell32.dll")]
        static extern void SHChangeNotify(int eventId, int flags, IntPtr item1, IntPtr item2);

        /// <summary>関連付けが変わったことをエクスプローラーに知らせる (アイコンの表示を更新させる)</summary>
        static void Notify()
        {
            try { SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero); } catch (Exception) { }
        }
    }
}
