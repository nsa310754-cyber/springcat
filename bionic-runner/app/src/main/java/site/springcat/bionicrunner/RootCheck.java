package site.springcat.bionicrunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * root (su) が使えるかの確認。使えない場合に「su が無い」のか「拒否された」のかを区別する。
 * Magisk はここで su を呼んだ時点で許可の確認画面を出す。KernelSU と APatch は確認画面を
 * 出さず、管理アプリで許可するまで su そのものがアプリから見えない。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class RootCheck {

    enum State {
        /** uid 0 で実行できた */
        GRANTED,
        /** su はあるが、uid 0 をもらえなかった (拒否・時間切れ) */
        DENIED,
        /** su が見つからない */
        NOT_FOUND
    }

    static final class Result {
        final State state;
        /** 実行した su の場所 (見つからなければ null) */
        final String suPath;
        /** su が出力した文字 (拒否の理由など)。無ければ空 */
        final String output;
        /** ブートローダーの状態。root が使えなかったときだけ調べる (それ以外は null) */
        Boot boot;

        Result(State state, String suPath, String output) {
            this.state = state;
            this.suPath = suPath;
            this.output = output;
        }
    }

    /** ブートローダーの状態と、Magisk 本体が動いている形跡 */
    static final class Boot {
        /** ro.boot.verifiedbootstate (green = ロック、orange = アンロック)。読めなければ空 */
        String verifiedBootState = "";
        /** ro.boot.flash.locked (1 = ロック、0 = アンロック)。読めなければ空 */
        String flashLocked = "";
        /** ro.boot.vbmeta.device_state (locked / unlocked)。読めなければ空 */
        String deviceState = "";
        /** Magisk 本体のコマンドが見えるか (動いていれば su と一緒に置かれる) */
        boolean magiskBinary;

        /** ロックされているなら TRUE、アンロックなら FALSE、判断できなければ null */
        Boolean locked() {
            if (flashLocked.equals("1") || deviceState.equals("locked")
                    || verifiedBootState.equals("green")) return Boolean.TRUE;
            if (flashLocked.equals("0") || deviceState.equals("unlocked")
                    || verifiedBootState.equals("orange")) return Boolean.FALSE;
            return null;
        }

        String describe() {
            return "verifiedbootstate=" + show(verifiedBootState) + ", flash.locked=" + show(flashLocked)
                    + ", device_state=" + show(deviceState)
                    + ", magisk コマンド=" + (magiskBinary ? "あり" : "なし");
        }

        private static String show(String v) {
            return v.isEmpty() ? "(読めない)" : v;
        }
    }

    static Boot boot() {
        Boot b = new Boot();
        b.verifiedBootState = prop("ro.boot.verifiedbootstate");
        b.flashLocked = prop("ro.boot.flash.locked");
        b.deviceState = prop("ro.boot.vbmeta.device_state");
        b.magiskBinary = findIn("magisk") != null;
        return b;
    }

    /** システムプロパティを getprop コマンドで読む。読めなければ空文字 */
    static String prop(String name) {
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            p.waitFor(5, TimeUnit.SECONDS);
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** PATH に無いことがある su の置き場所 (root 化の方式や古い端末によって違う) */
    private static final String[] EXTRA_DIRS = {
            "/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin",
            "/debug_ramdisk", "/data/adb/ksu/bin", "/data/adb/ap/bin", "/data/adb/magisk",
            "/magisk/.core/bin", "/system/bin/.ext", "/data/local/xbin", "/data/local/bin",
    };

    private static volatile String cached;
    private static volatile Result last;

    /** 直近の確認結果。まだ一度も確認していなければ null */
    static Result last() {
        return last;
    }

    private RootCheck() {
    }

    /** su を起動するときに使う名前。場所が分かっていれば絶対パス、分からなければ "su" */
    static String su() {
        String c = cached;
        return c != null ? c : "su";
    }

    /** PATH と、よくある置き場所から su を探す。無ければ null */
    static String find() {
        return findIn("su");
    }

    private static String findIn(String name) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                if (!dir.isEmpty() && new File(dir, name).exists()) return new File(dir, name).getPath();
            }
        }
        for (String dir : EXTRA_DIRS) {
            if (new File(dir, name).exists()) return new File(dir, name).getPath();
        }
        return null;
    }

    /**
     * 実際に su を呼んで確かめる。Magisk の確認画面が出ている間はここで待つので、
     * UI スレッド以外から呼ぶこと。
     */
    static Result check() {
        Result r = run();
        if (r.state != State.GRANTED) r.boot = boot();
        last = r;
        return r;
    }

    private static Result run() {
        String found = find();
        // 見つからなくても名前だけで一度試す: ファイルの有無を調べられない場所にあることもある
        String su = found != null ? found : "su";
        Process p = null;
        try {
            p = new ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start();
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            String first = null;
            while ((line = r.readLine()) != null) {
                if (first == null) first = line.trim();
                if (out.length() < 400) out.append(line).append('\n');
            }
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0 && "0".equals(first)) {
                cached = su;
                return new Result(State.GRANTED, su, "");
            }
            return new Result(State.DENIED, su, out.toString().trim());
        } catch (IOException e) {
            // 起動そのものができない = その場所に実行できる su が無い
            return new Result(found == null ? State.NOT_FOUND : State.DENIED, found,
                    found == null ? "" : String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(State.DENIED, su, "中断されました");
        } finally {
            if (p != null) p.destroy();
        }
    }
}
