package site.springcat.bionicrunner;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * スクリプトを Android で動く形にする。
 *
 * PC の Linux 用に書かれたスクリプトは、たいてい 1 行目が #!/bin/bash や #!/usr/bin/env python3 で
 * 始まる。Android にはその場所 (/bin や /usr/bin) が無いので、そのまま実行すると
 * 「inaccessible or not found」になる。Windows の改行 (CR LF) が混ざっていても、1 行目の終わりに
 * 見えない文字 (CR) が付いて、同じエラーになる。登録するときにこれらを直す。
 */
final class Scripts {

    /** 書き換える大きさの上限 (これより大きいものはスクリプトではないとみなす) */
    private static final long MAX = 4L * 1024 * 1024;

    private Scripts() {
    }

    /**
     * #! 行の中身 ("/usr/bin/env python3 -u" など) から、この端末で動く実行の仕方を返す。
     * 戻り値は {実行するもの, 追加の引数 (無ければ空文字)}。分からなければ null。
     */
    static String[] interpreter(Context c, String shebang) {
        return interpreter(Tools.dir(c), shebang);
    }

    /** tools は同梱の処理系へのリンクの置き場所 ({@link Tools#dir}) */
    static String[] interpreter(File tools, String shebang) {
        String line = shebang.trim();
        if (line.isEmpty()) return new String[]{"/system/bin/sh", ""};
        String[] parts = line.split("\\s+");
        String prog = parts[0];
        int next = 1;
        // #!/usr/bin/env [オプション] プログラム … の形: 本当に動かすのは、その後ろのプログラム
        if (prog.endsWith("/env") || prog.equals("env")) {
            while (next < parts.length && parts[next].startsWith("-")) next++;
            if (next >= parts.length) return null;
            prog = parts[next++];
        }
        StringBuilder args = new StringBuilder();
        for (int i = next; i < parts.length; i++) args.append(i > next ? " " : "").append(parts[i]);
        String base = prog.substring(prog.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String found = known(tools, base);
        if (found != null) return new String[]{found, args.toString()};
        // 端末にその場所が本当にあれば (#!/system/bin/sh など)、そのまま使う
        if (prog.startsWith("/") && new File(prog).exists()) return new String[]{prog, args.toString()};
        return null;
    }

    /** 名前の知られた処理系を、この端末で動くものに置き換える。知らない名前なら null */
    private static String known(File tools, String name) {
        // 端末のシェル (mksh)。bash 専用の書き方は動かないことがあるが、多くはそのまま動く
        if (name.matches("(ba|da|a|k|mk|z)?sh")) return "/system/bin/sh";
        if (name.matches("python(3(\\.\\d+)?)?")) return new File(tools, "python3").getAbsolutePath();
        if (name.matches("lua(5\\.\\d)?")) return new File(tools, "lua").getAbsolutePath();
        return null;
    }

    /**
     * f を Android で動く形に書き換える。Windows の改行を Unix の改行に直し、1 行目の #! を
     * この端末にある処理系に置き換える。何か直したら、その説明を返す (直すところが無ければ null)。
     */
    static String normalize(Context c, File f) throws IOException {
        return normalize(Tools.dir(c), f);
    }

    static String normalize(File tools, File f) throws IOException {
        if (f.length() > MAX) return null;
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            data = bo.toByteArray();
        }
        if (data.length < 2 || data[0] != '#' || data[1] != '!') return null;
        String text = new String(data, StandardCharsets.UTF_8);
        StringBuilder what = new StringBuilder();
        if (text.contains("\r\n")) {
            text = text.replace("\r\n", "\n");
            what.append("Windows の改行 (CR LF) を直しました。");
        }
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        String shebang = first.substring(2).trim();
        String[] how = interpreter(tools, shebang);
        if (how != null) {
            String fixed = "#!" + how[0] + (how[1].isEmpty() ? "" : " " + how[1]);
            if (!fixed.equals(first)) {
                text = fixed + (nl < 0 ? "\n" : text.substring(nl));
                what.append("1 行目を ").append(first).append(" から ").append(fixed)
                        .append(" に直しました (Android には元の場所が無いため)。");
            }
        }
        if (what.length() == 0) return null;
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return what.toString();
    }

    /**
     * root が無いときにスクリプトを動かすコマンド (処理系 + ファイル)。アプリのデータ領域にある
     * ファイルは、root が無いと直接は実行できないので、処理系に読み込ませる形にする。
     */
    static String runCommand(Context c, File script, String shebang) {
        String[] how = shebang == null ? null : interpreter(c, shebang);
        String interp = how == null ? "/system/bin/sh" : how[0];
        String extra = how == null || how[1].isEmpty() ? "" : " " + how[1];
        return MainActivity.sq(interp) + extra + " " + MainActivity.sq(script.getAbsolutePath());
    }
}
