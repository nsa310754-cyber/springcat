// .NET (C#) のプログラムの確認用 (コマンドプロンプト用)。Bionic Runner の Wine (Wine Mono) で動かす。
// .NET の版・64bit で動いているか・簡単な計算 (LINQ)・日本語を出す。害はありません (ファイルを書かない)。
// ファイルの中身は ASCII だけ (日本語は \u の番号で書く)。
using System;
using System.Linq;

class HelloDotNet
{
    static void Main(string[] args)
    {
        Console.WriteLine("Hello from .NET! (Wine Mono)");
        Console.WriteLine("CLR: " + Environment.Version + "  64bit process: " + Environment.Is64BitProcess);
        Console.WriteLine("OS: " + Environment.OSVersion);
        int sum = Enumerable.Range(1, 100).Sum();
        Console.WriteLine("1 + 2 + ... + 100 = " + sum);
        // こんにちは = こんにちは
        Console.WriteLine("日本語: こんにちは、.NET");
        for (int i = 0; i < args.Length; i++) Console.WriteLine("arg " + (i + 1) + ": " + args[i]);
    }
}
