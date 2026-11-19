using System;
using System.IO;
using System.Linq;
using System.Text;

namespace ExecSaver
{
    /// <summary>ExecSaver.exe --selftest (Mono で実行)。コマンドラインの解釈・形式判定・保存を確かめる。</summary>
    static class SelfTest
    {
        static int fail;
        static void Ok(bool c, string m) { Console.WriteLine((c ? "PASS " : "FAIL ") + m); if (!c) fail++; }
        static void Eq(string got, string want, string m) { Ok(got == want, m + " → " + got + (got == want ? "" : " (期待 " + want + ")")); }

        static string Ext(string text, string hint = null) { string l; return Format.Detect(Encoding.UTF8.GetBytes(text), hint, out l); }

        public static int Run(string selfExe)
        {
            try { Console.OutputEncoding = new UTF8Encoding(false); } catch { }
            string tmp = Path.Combine(Path.GetTempPath(), "execsaver-test-" + Guid.NewGuid().ToString("N").Substring(0, 8));
            string work = Path.Combine(tmp, "work"), root = Path.Combine(tmp, "保存先"), sys = Path.Combine(tmp, "Windows");
            Directory.CreateDirectory(work); Directory.CreateDirectory(sys);
            try
            {
                // ---- コマンドラインの区切り ----
                var t = CmdLine.Split("a.exe \"C:\\Program Files\\x y.bat\" b\\\\\"c d\" e\\\"f \"\"g");
                Eq(string.Join("|", t.Select(x => x.Text)), "a.exe|C:\\Program Files\\x y.bat|b\\c d|e\"f|g", "引用符と \\ の規則");

                // ---- テスト用ファイル ----
                string bat = Path.Combine(work, "セットアップ.bat"); File.WriteAllText(bat, "@echo off\r\nset X=1\r\ngoto end\r\n:end\r\n");
                string ps1 = Path.Combine(work, "tool.ps1"); File.WriteAllText(ps1, "param($a)\r\n$x = Get-ChildItem\r\nWrite-Host $x\r\n");
                string vbs = Path.Combine(work, "hello.vbs"); File.WriteAllText(vbs, "Dim x\r\nSet sh = CreateObject(\"WScript.Shell\")\r\nMsgBox \"hi\"\r\n");
                string fakeTxt = Path.Combine(work, "readme.txt"); File.WriteAllText(fakeTxt, "import os\nimport sys\ndef main():\n    print('hi')\nif __name__ == '__main__':\n    main()\n");
                string noext = Path.Combine(work, "payload"); File.Copy(selfExe, noext);
                string py = Path.Combine(work, "job.py"); File.WriteAllText(py, "print('x')\n");
                string jar = Path.Combine(work, "app.jar");
                File.WriteAllBytes(jar, new byte[] { 0x50, 0x4B, 3, 4 }.Concat(Encoding.ASCII.GetBytes("....META-INF/MANIFEST.MF....")).ToArray());
                string sysExe = Path.Combine(sys, "notepad.exe"); File.Copy(selfExe, sysExe);

                // ---- 実行されたコードの割り出し ----
                Target r;
                r = Resolver.Resolve(@"C:\Windows\System32\cmd.exe", "C:\\Windows\\system32\\cmd.exe /c \"\"" + bat + "\" arg1\"", null);
                Eq(r.Path, bat, "cmd /c \"\"x.bat\" 引数\" → バッチファイル");
                r = Resolver.Resolve(@"C:\Windows\System32\cmd.exe", "cmd.exe /d /c セットアップ", work);
                Eq(r.Path, bat, "cmd /c 名前 (拡張子なし・作業フォルダー基準) → .bat を探す");
                r = Resolver.Resolve(@"C:\Windows\System32\cmd.exe", "cmd /c echo hello & del x.txt", null);
                Ok(r.IsInline && Encoding.UTF8.GetString(r.Inline) == "echo hello & del x.txt" && r.Name == "cmd", "cmd /c 直接書いたコマンド → インライン");
                r = Resolver.Resolve(@"C:\Windows\System32\cmd.exe", "\"C:\\Windows\\system32\\cmd.exe\" ", null);
                Ok(r.Note != null && r.Note.StartsWith("SKIP"), "対話型 cmd は対象外");

                r = Resolver.Resolve(@"C:\x\powershell.exe", "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\" -a 1", null);
                Eq(r.Path, ps1, "powershell -ExecutionPolicy Bypass -File x.ps1");
                string enc = Convert.ToBase64String(Encoding.Unicode.GetBytes("Write-Host 'secret'; Get-Process"));
                r = Resolver.Resolve(@"C:\x\powershell.exe", "powershell -nop -w hidden -enc " + enc, null);
                Ok(r.IsInline && Encoding.UTF8.GetString(r.Inline) == "Write-Host 'secret'; Get-Process" && r.HintExt == ".ps1", "powershell -enc (Base64) を元のコードに戻す");
                r = Resolver.Resolve(@"C:\x\pwsh.exe", "pwsh -c Get-Date; Write-Output 1", null);
                Ok(r.IsInline && Encoding.UTF8.GetString(r.Inline) == "Get-Date; Write-Output 1", "pwsh -c 複数語のコマンド");
                r = Resolver.Resolve(@"C:\x\powershell.exe", "powershell \"" + ps1 + "\"", null);
                Eq(r.Path, ps1, "powershell x.ps1 (オプションなし)");

                r = Resolver.Resolve(@"C:\Windows\System32\wscript.exe", "\"C:\\Windows\\System32\\WScript.exe\" //nologo \"" + vbs + "\"", null);
                Eq(r.Path, vbs, "wscript //nologo x.vbs");
                r = Resolver.Resolve(@"C:\py\python.exe", "python -u -W ignore job.py --x", work);
                Eq(r.Path, py, "python -u -W ignore job.py");
                r = Resolver.Resolve(@"C:\py\python.exe", "python -c \"import os; print(1)\"", work);
                Ok(r.IsInline && r.HintExt == ".py" && r.Name == "python", "python -c → インライン");
                r = Resolver.Resolve(@"C:\java\javaw.exe", "javaw -Xmx1g -jar \"" + jar + "\"", null);
                Eq(r.Path, jar, "javaw -jar x.jar");
                r = Resolver.Resolve(@"C:\Windows\System32\rundll32.exe", "rundll32.exe " + noext + ",DllMain 1", null);
                Eq(r.Path, noext, "rundll32 x,関数 → DLL 本体");
                r = Resolver.Resolve(@"C:\Windows\System32\mshta.exe", "mshta \"javascript:alert(1);close()\"", null);
                Ok(r.IsInline && r.HintExt == ".js", "mshta javascript: → インライン");
                r = Resolver.Resolve(Path.Combine(work, "game.exe"), "game.exe -fullscreen", null);
                Eq(r.Path, Path.Combine(work, "game.exe"), "普通の exe → exe そのもの");
                Eq(r.Name, "game", "名前は拡張子なし");

                // ---- 形式の判定 ----
                string label;
                Eq(Format.Detect(File.ReadAllBytes(selfExe), null, out label), ".exe", "PE 実行ファイル (" + label + ")");
                Ok(label.Contains(".NET"), ".NET を見分ける");
                Eq(Ext("@echo off\r\nrem test\r\nset A=1\r\n"), ".bat", "バッチ");
                Eq(Ext("$a = Get-Item .\nWrite-Host $a\n"), ".ps1", "PowerShell");
                Eq(Ext("Dim s\nSet o = CreateObject(\"Scripting.FileSystemObject\")\nWScript.Echo s\n"), ".vbs", "VBScript");
                Eq(Ext("var fso = new ActiveXObject('Scripting.FileSystemObject');\nfunction f(){ return 1; }\n"), ".js", "JScript");
                Eq(Ext("import os\ndef f(x):\n    return x\nprint(f(1))\n"), ".py", "Python");
                Eq(Ext("#!/usr/bin/env python3\nprint(1)\n"), ".py", "shebang");
                Eq(Ext("<html><head><HTA:APPLICATION id=x></head></html>"), ".hta", "HTA");
                Eq(Ext("<?xml version=\"1.0\"?>\n<job id=\"a\"><script language=\"VBScript\">x</script></job>"), ".wsf", "WSF");
                Eq(Ext("Windows Registry Editor Version 5.00\r\n[HKEY_CURRENT_USER\\x]\r\n"), ".reg", "REG");
                Eq(Ext("Get-Date", ".ps1"), ".ps1", "短いコード + 元の拡張子");
                Eq(Ext("echo hi", ".bat"), ".bat", "1 行のバッチ + 元の拡張子");
                Eq(Ext("hello world"), ".txt", "ただの文章 → .txt");
                Eq(Format.Detect(File.ReadAllBytes(jar), ".zip", out label), ".jar", "ZIP の中身が Java → .jar");
                Encoding sjis = null; try { sjis = Encoding.GetEncoding(932); } catch { Console.WriteLine("SKIP Shift-JIS (この Mono に文字コード表がない。Windows には必ずある)"); }
                if (sjis != null) Eq(Format.Detect(sjis.GetBytes("@echo off\r\necho こんにちは\r\nrem 日本語\r\n"), null, out label), ".bat", "Shift-JIS のバッチ");
                Eq(Format.Detect(new byte[] { 1, 2, 0, 0, 5, 200, 0, 9 }, null, out label), ".bin", "不明なバイナリ → .bin");

                // ---- 保存 ----
                var saver = new Saver(root);
                saver.ExcludeDirs.Add(sys);
                SaveResult s;
                s = saver.Save(Resolver.Resolve(@"C:\x\cmd.exe", "cmd /c \"" + bat + "\"", null), "cmd /c x");
                Eq(s.SavedPath, Path.Combine(root, "セットアップ", "セットアップ.bat"), "保存先\\名前\\名前.bat");
                Ok(File.ReadAllText(s.SavedPath) == File.ReadAllText(bat), "中身が同じ");
                s = saver.Save(Resolver.Resolve(@"C:\x\cmd.exe", "cmd /c \"" + bat + "\"", null), "cmd /c x");
                Ok(!s.Saved && Directory.GetFiles(Path.Combine(root, "セットアップ"), "*.bat").Length == 1, "2 回目は保存しない (" + s.Message + ")");
                File.AppendAllText(bat, "echo changed\r\n");
                s = saver.Save(Resolver.Resolve(@"C:\x\cmd.exe", "cmd /c \"" + bat + "\"", null), "cmd /c x");
                Eq(Path.GetFileName(s.SavedPath), "セットアップ_2.bat", "中身が変わったら _2 で保存");
                Ok(File.ReadAllText(Path.Combine(root, "セットアップ", "実行記録.txt")).Contains("cmd /c x"), "実行記録.txt にコマンドラインを残す");

                s = saver.Save(Resolver.Resolve(noext, "payload", null), "payload");
                Eq(Path.GetFileName(s.SavedPath), "payload.exe", "拡張子なしの実行ファイル → 中身から .exe");
                s = saver.Save(Resolver.Resolve(@"C:\py\python.exe", "python \"" + fakeTxt + "\"", null), "python readme.txt");
                Eq(Path.GetFileName(s.SavedPath), "readme.py", "readme.txt の中身が Python → readme.py");
                s = saver.Save(Resolver.Resolve(@"C:\x\powershell.exe", "powershell -enc " + enc, null), "powershell -enc ...");
                Eq(s.SavedPath, Path.Combine(root, "powershell", "powershell.ps1"), "-enc のコードを powershell\\powershell.ps1");
                Ok(File.ReadAllText(s.SavedPath) == "Write-Host 'secret'; Get-Process", "デコード後のコードを保存");

                s = saver.Save(Resolver.Resolve(sysExe, "notepad.exe", null), "notepad");
                Ok(!s.Saved && s.Message.StartsWith("対象外"), "Windows フォルダー内は除外 (" + s.Message + ")");
                saver.Exclude = false;
                s = saver.Save(Resolver.Resolve(sysExe, "notepad.exe", null), "notepad");
                Ok(s.Saved, "除外を切れば保存する");
                saver.MaxBytes = 100;
                s = saver.Save(Resolver.Resolve(noext + "x", "y", null), "y");
                Ok(!s.Saved, "無いファイルは保存しない (" + s.Message + ")");
                File.Copy(noext, noext + "_big");
                s = saver.Save(Resolver.Resolve(noext + "_big", "y", null), "y");
                Ok(!s.Saved && s.Message.Contains("大きすぎる"), "上限より大きいファイルは保存しない");
                s = saver.Save(Resolver.Resolve(Path.Combine(root, "payload", "payload.exe"), "x", null), "x");
                Ok(!s.Saved && s.Message.Contains("保存先"), "保存先の中のファイルを実行しても保存しない");
                Eq(Saver.SafeName("a:b*c?.exe."), "a_b_c_.exe", "使えない文字を置き換え");
            }
            catch (Exception ex) { Console.WriteLine("FAIL 例外: " + ex); fail++; }
            finally { try { Directory.Delete(tmp, true); } catch { } }
            Console.WriteLine(fail == 0 ? "\nすべて成功" : "\n" + fail + " 件失敗");
            return fail == 0 ? 0 : 1;
        }
    }
}
