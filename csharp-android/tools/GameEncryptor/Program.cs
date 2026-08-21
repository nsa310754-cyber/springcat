using System.Security.Cryptography;
using System.Text;

if (args.Length != 3)
{
    Console.Error.WriteLine("usage: GameEncryptor <input.html> <output.enc> <passphrase>");
    return 1;
}

string inputPath = args[0];
string outputPath = args[1];
string passphrase = args[2];

byte[] plain = File.ReadAllBytes(inputPath);
byte[] key = SHA256.HashData(Encoding.UTF8.GetBytes(passphrase));

using Aes aes = Aes.Create();
aes.KeySize = 256;
aes.Key = key;
aes.Mode = CipherMode.CBC;
aes.Padding = PaddingMode.PKCS7;
aes.GenerateIV();

byte[] cipher = aes.EncryptCbc(plain, aes.IV, PaddingMode.PKCS7);

// Output layout: 16-byte IV followed by the ciphertext. MainActivity reads it back the
// same way at startup and decrypts in memory before handing the HTML string to WebView.
byte[] output = new byte[aes.IV.Length + cipher.Length];
Buffer.BlockCopy(aes.IV, 0, output, 0, aes.IV.Length);
Buffer.BlockCopy(cipher, 0, output, aes.IV.Length, cipher.Length);

Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath))!);
File.WriteAllBytes(outputPath, output);

Console.WriteLine($"[GameEncryptor] {inputPath} ({plain.Length} bytes) -> {outputPath} ({output.Length} bytes)");
return 0;
