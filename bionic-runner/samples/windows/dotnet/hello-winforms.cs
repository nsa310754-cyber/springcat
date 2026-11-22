// .NET (C#) のウィンドウのプログラムの確認用 (Windows フォーム)。Bionic Runner の Wine (Wine Mono) で動かす。
// ボタンを押すと数を数え、日本語の文字も出す。害はありません。ファイルの中身は ASCII だけ (日本語は \u の番号)。
using System;
using System.Drawing;
using System.Windows.Forms;

class HelloWinForms : Form
{
    int count;
    Label label;

    HelloWinForms()
    {
        Text = ".NET + Wine Mono";
        ClientSize = new Size(420, 180);
        label = new Label { Left = 20, Top = 20, Width = 380, Height = 40,
            Text = "こんにちは、Windows フォーム (.NET)" }; // こんにちは、Windows フォーム
        var button = new Button { Left = 20, Top = 80, Width = 200, Height = 40,
            Text = "押してください" }; // 押してください
        button.Click += (s, e) => { count++; label.Text = "押した回数: " + count; }; // 押した回数
        Controls.Add(label);
        Controls.Add(button);
    }

    [STAThread]
    static void Main()
    {
        Application.EnableVisualStyles();
        Application.Run(new HelloWinForms());
    }
}
