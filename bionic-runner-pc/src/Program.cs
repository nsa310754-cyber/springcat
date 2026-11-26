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
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]
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

            bool first;
            using (new Mutex(true, "BionicRunnerPC-single-instance", out first))
            {
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
