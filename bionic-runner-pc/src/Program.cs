using System;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("Bionic Runner PC")]
[assembly: AssemblyDescription("Windows で Linux (glibc) と Android (bionic) の実行ファイルを動かす")]
[assembly: AssemblyProduct("Bionic Runner PC")]
[assembly: AssemblyCompany("springcat")]
[assembly: AssemblyCopyright("springcat")]
[assembly: AssemblyVersion("1.3.0.0")]
[assembly: AssemblyFileVersion("1.3.0.0")]
[assembly: ComVisible(false)]

namespace BionicRunnerPC
{
    static class Program
    {
        [DllImport("user32.dll")]
        static extern bool SetProcessDPIAware();

        [STAThread]
        static void Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch (Exception) { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            // デスクトップの「Linux (glibc)」「Termux (bionic)」アイコンから: 端末を開いて終わる。
            // 管理者として実行されていれば root (#)、そうでなければ一般ユーザー ($)
            if (args.Length >= 2 && args[0] == "--terminal")
            {
                try
                {
                    Wsl.OpenTerminal(args[1] == "termux");
                }
                catch (Exception e)
                {
                    MessageBox.Show("端末を開けませんでした: " + e.Message, Installer.AppName);
                }
                return;
            }

            // エクスプローラーでダブルクリック / 右クリック →「Linux で実行」から
            if (args.Length >= 2 && args[0] == "--open")
            {
                Launcher.OpenFromExplorer(args[1]);
                return;
            }

            using (var mutex = new Mutex(false, "BionicRunnerPC-single-instance"))
            {
                bool first;
                try
                {
                    // 「管理者として開き直す」のときは、前のウィンドウが閉じるのを少し待つ
                    first = mutex.WaitOne(Array.IndexOf(args, "--elevated-restart") >= 0 ? 10000 : 0);
                }
                catch (AbandonedMutexException)
                {
                    first = true;
                }
                if (!first)
                {
                    MessageBox.Show(Installer.AppName + " は既に起動しています。", Installer.AppName);
                    return;
                }
                bool iconCreated = false;
                try
                {
                    // 実行したらデスクトップ (ホーム画面) とスタートメニューにアイコンを追加する
                    iconCreated = Installer.EnsureInstalled();
                }
                catch (Exception e)
                {
                    MessageBox.Show("アイコンを作成できませんでした: " + e.Message, Installer.AppName);
                }
                Application.Run(new MainForm(args, iconCreated));
            }
        }
    }
}
