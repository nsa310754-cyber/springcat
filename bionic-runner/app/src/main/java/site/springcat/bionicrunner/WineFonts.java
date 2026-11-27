package site.springcat.bionicrunner;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Wine の日本語の表示 (字体と文字コード)。
 *
 * 字体: Linux の追加機能の「日本語フォント (Noto CJK)」が入っていれば、それを Wine から使う。
 * 字体のファイルは Wine の中へコピーせず、wine.sh が files/wine/fonts-ja にリンクを作って
 * fontconfig に教える (容量を増やさない)。
 * Windows のプログラムが名前で指定する日本語の字体 (MS ゴシック・メイリオ・MS 明朝など) は、
 * レジストリの FontSubstitutes (置き換え) で Noto Sans CJK JP / Noto Serif CJK JP に割り当てる。
 * 英語の字体 (Tahoma など、ボタンやメニューに使われる) には、FontLink (足りない文字を別の字体で補う設定) で
 * Noto CJK を付ける。レジストリへの書き込みは wine.sh が regedit で行う (設定が変わったときだけ)。
 *
 * 文字コード: 設定で「日本語の環境」を選ぶと、wine.sh が LANG=ja_JP.UTF-8 で Wine を動かす
 * (Windows の文字コードが日本語 (932) になる)。そのための glibc のロケールは assets/wine/locale-ja.zip
 * (native/build-ja-locale.sh で作る) を files/wine/locale に展開して使う。
 */
final class WineFonts {

    /** 追加機能の日本語フォントが入る場所 (Linux 環境の中) */
    static final String FONT_DIR = "usr/share/fonts/noto";
    static final String SANS_FILE = "NotoSansCJK-Regular.ttc";
    static final String SERIF_FILE = "NotoSerifCJK-Regular.ttc";
    static final String SANS = "Noto Sans CJK JP";
    static final String SERIF = "Noto Serif CJK JP";
    static final String MONO = "Noto Sans Mono CJK JP";

    /** ゴシック体 (Noto Sans CJK JP) に割り当てる字体の名前 */
    static final String[] SANS_NAMES = {
            "MS Gothic", "MS PGothic", "MS UI Gothic",
            "ＭＳ ゴシック",             // ＭＳ ゴシック
            "ＭＳ Ｐゴシック",       // ＭＳ Ｐゴシック
            "Meiryo", "Meiryo UI", "メイリオ",   // メイリオ
            "メイリオ UI",                       // メイリオ UI
            "Yu Gothic", "Yu Gothic UI", "Yu Gothic Medium",
            "游ゴシック",                    // 游ゴシック
            "游ゴシック Medium",             // 游ゴシック Medium
    };
    /** 明朝体 (Noto Serif CJK JP) に割り当てる字体の名前 */
    static final String[] SERIF_NAMES = {
            "MS Mincho", "MS PMincho",
            "ＭＳ 明朝",                         // ＭＳ 明朝
            "ＭＳ Ｐ明朝",                   // ＭＳ Ｐ明朝
            "Yu Mincho", "游明朝",                   // 游明朝
    };
    /** 足りない文字 (日本語) を Noto CJK で補う英語の字体。値は {名前, 補う字体} */
    static final String[][] LINKS = {
            {"Tahoma", SANS}, {"Microsoft Sans Serif", SANS}, {"Segoe UI", SANS},
            {"Arial", SANS}, {"Verdana", SANS}, {"Lucida Sans Unicode", SANS},
            {"Times New Roman", SERIF},
            {"Courier New", MONO}, {"Lucida Console", MONO}, {"Consolas", MONO},
    };

    private WineFonts() {
    }

    // ------------------------------------------------------------ レジストリ

    /** wine.sh が regedit で読み込むレジストリのファイルの中身 (文字列) */
    static String regText() {
        StringBuilder sb = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
        sb.append("; Bionic Runner: 日本語の字体の設定 (自動生成。アプリが Wine を動かすときに読み込む)\r\n\r\n");
        // 置き換え: プログラムが MS ゴシックなどを指定したら、Noto CJK を使う
        sb.append("[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes]\r\n");
        for (String n : SANS_NAMES) sb.append(q(n)).append('=').append(q(SANS)).append("\r\n");
        for (String n : SERIF_NAMES) sb.append(q(n)).append('=').append(q(SERIF)).append("\r\n");
        // Wine 独自の置き換え (その名前の字体が無いときだけ、別名として使う)
        sb.append("\r\n[HKEY_CURRENT_USER\\Software\\Wine\\Fonts\\Replacements]\r\n");
        for (String n : SANS_NAMES) sb.append(q(n)).append('=').append(q(SANS)).append("\r\n");
        for (String n : SERIF_NAMES) sb.append(q(n)).append('=').append(q(SERIF)).append("\r\n");
        // 足りない文字の補い: 英語の字体に無い日本語を Noto CJK で出す
        sb.append("\r\n[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink]\r\n");
        for (String[] l : LINKS) {
            String file = l[1].equals(SERIF) ? SERIF_FILE : SANS_FILE;
            sb.append(q(l[0])).append("=hex(7):").append(multiSz(file + "," + l[1])).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /** regText を、regedit が読める形 (先頭に印の付いた UTF-16LE) にしたもの */
    static byte[] regBytes() {
        byte[] body = regText().getBytes(StandardCharsets.UTF_16LE);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) 0xff;
        out[1] = (byte) 0xfe;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 文字列 1 つの REG_MULTI_SZ を、.reg の hex(7) の形 (UTF-16LE、最後に 0 が 2 つ) にする */
    static String multiSz(String s) {
        byte[] b = (s + "\0\0").getBytes(StandardCharsets.UTF_16LE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 字体のありか

    /** 追加機能の日本語フォントが入っているか */
    static boolean addonInstalled(Context c) {
        return new File(new File(Linux.rootfs(c), FONT_DIR), SANS_FILE).isFile();
    }

    /** Android 自体に日本語の字体があるか (多くの機種にある。追加機能が無いときの代わりに使う) */
    static boolean systemFont() {
        return new File("/system/fonts/" + SANS_FILE).isFile();
    }

    /** 日本語の字体が無いときの案内。入っていれば null */
    static String notice(Context c) {
        if (addonInstalled(c)) return null;
        return "日本語を表示するには、追加機能の「日本語フォント」を入れてください"
                + (systemFont() ? " (入れるまでは Android の字体で代わりに表示します。字体によっては□になります)"
                : " (入れるまでは日本語が□になります)")
                + "。X11 の画面の「追加機能」→「日本語フォント (Noto CJK)」で入れられます。";
    }

    // ------------------------------------------------------------ 日本語の環境 (LANG)

    private static File langFlag(Context c) {
        return new File(Wine.dir(c), "lang-ja");
    }

    /** 設定:「日本語の環境」で Wine を動かすか (LANG=ja_JP.UTF-8)。ふだんは切 */
    static boolean japaneseLocale(Context c) {
        return langFlag(c).isFile();
    }

    static void setJapaneseLocale(Context c, boolean on) throws IOException {
        File f = langFlag(c);
        if (on) {
            Wine.dir(c).mkdirs();
            Glibc.writeText(f, "");
        } else if (f.exists() && !f.delete()) {
            throw new IOException("設定を書き換えられません");
        }
    }

    // ------------------------------------------------------------ ファイルを置く

    /**
     * files/wine に、レジストリのファイル (jpfont.reg) と日本語のロケール (locale/ja_JP.UTF-8) を置く。
     * アプリの起動のたびに呼ぶ (中身が同じなら書き直さない)
     */
    static void writeFiles(Context c) {
        File dir = Wine.dir(c);
        if (!dir.isDirectory()) return; // Wine を入れていない
        try {
            writeIfChanged(new File(dir, "jpfont.reg"), regBytes());
        } catch (IOException ignored) {
            // 書けなければ、日本語の字体の設定をしないだけ
        }
        try {
            byte[] zip;
            try (InputStream in = c.getAssets().open("wine/locale-ja.zip")) {
                zip = Icons.readAll(in);
            }
            File locale = new File(dir, "locale");
            File mark = new File(locale, "ja_JP.UTF-8/.br-size");
            String want = String.valueOf(zip.length);
            if (want.equals(readSmall(mark))) return;
            File tmp = new File(dir, "locale.new");
            Archive.deleteTree(tmp);
            unzip(zip, tmp);
            Glibc.writeText(new File(tmp, "ja_JP.UTF-8/.br-size"), want);
            Archive.deleteTree(locale);
            if (!tmp.renameTo(locale)) throw new IOException("ロケールを置けません");
        } catch (IOException ignored) {
            // 置けなければ、日本語の環境は使えない (wine.sh はふだんの環境で動かす)
        }
    }

    private static void writeIfChanged(File f, byte[] data) throws IOException {
        if (f.isFile() && f.length() == data.length) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                if (java.util.Arrays.equals(Icons.readAll(in), data)) return;
            }
        }
        File tmp = new File(f.getParentFile(), f.getName() + ".new");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(data);
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("書けません: " + f.getName());
        }
    }

    private static String readSmall(File f) {
        if (!f.isFile() || f.length() > 64) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(Icons.readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** zip を dest に展開する (中の名前は「ja_JP.UTF-8/…」だけを受け付ける) */
    private static void unzip(byte[] zip, File dest) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !name.startsWith("ja_JP.UTF-8/") || name.contains("..")) continue;
                File out = new File(dest, name);
                out.getParentFile().mkdirs();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = z.read(buf)) > 0) bo.write(buf, 0, n);
                try (OutputStream os = new FileOutputStream(out)) {
                    os.write(bo.toByteArray());
                }
                out.setReadable(true, false);
            }
        }
    }
}
