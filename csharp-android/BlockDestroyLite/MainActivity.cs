using System.Security.Cryptography;
using System.Text;
using Android.Content.PM;
using Android.Webkit;

namespace BlockDestroyLite;

[Activity(Label = "@string/app_name", MainLauncher = true, ScreenOrientation = ScreenOrientation.Portrait)]
public class MainActivity : Activity
{
    // Must match tools/GameEncryptor's <GameEncPassphrase> in BlockDestroyLite.csproj exactly.
    // Split into parts so the full passphrase doesn't sit as one contiguous string in the binary.
    static readonly string[] GameEncPassphraseParts = { "bdlite-", "ragdollp-", "demoapp-", "obf-k7y" };

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);

        var webView = new WebView(this);
        webView.Settings.JavaScriptEnabled = true;
        webView.Settings.DomStorageEnabled = true;
        webView.SetWebViewClient(new WebViewClient());
        SetContentView(webView);

        string html = LoadAndDecryptGameHtml();
        webView.LoadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    string LoadAndDecryptGameHtml()
    {
        using Stream assetStream = Assets!.Open("game.enc");
        using var buffer = new MemoryStream();
        assetStream.CopyTo(buffer);
        byte[] data = buffer.ToArray();

        byte[] iv = data[..16];
        byte[] cipherText = data[16..];

        byte[] key = SHA256.HashData(Encoding.UTF8.GetBytes(string.Concat(GameEncPassphraseParts)));

        using Aes aes = Aes.Create();
        aes.KeySize = 256;
        aes.Key = key;
        aes.Mode = CipherMode.CBC;
        aes.Padding = PaddingMode.PKCS7;

        byte[] plain = aes.DecryptCbc(cipherText, iv, PaddingMode.PKCS7);
        return Encoding.UTF8.GetString(plain);
    }
}
