package site.springcat.bionicrunner;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 実行中のプログラムからの依頼 (br コマンド) の中身を処理する。アプリ全体で 1 つだけ存在する。
 *
 * できること:
 *  - overlay: ほかのアプリの上に文字を出す (通信速度の表示など)
 *  - ui / tap / swipe / key: 画面に出ているものを読み、ボタンを探して押す、タップする (自動操作)
 *  - net: 通信量と Wi-Fi の状態を返す
 *  - kill: アプリを強制終了する
 *
 * ほかのアプリを勝手に操作できる強い機能なので、設定でオンにするまで何も受け付けない。
 * 画面の読み取りとタップには、さらに利用者が Android の設定でユーザー補助を有効にする必要がある。
 */
final class Control implements ControlServer.Handler {

    private static Control instance;

    private final Context app;
    private final ControlServer server = new ControlServer(this);
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 画面の上に出している表示 (名前 → 部品)。UI スレッドだけが触る */
    private final Map<String, TextView> overlays = new HashMap<>();

    private Control(Context app) {
        this.app = app;
    }

    static synchronized Control get(Context context) {
        if (instance == null) {
            Control c = new Control(context.getApplicationContext());
            try {
                c.server.start();
            } catch (IOException e) {
                return null;
            }
            instance = c;
        }
        return instance;
    }

    /** プログラムに渡す接続先 (環境変数 BR_API の値) */
    String address() {
        return server.address();
    }

    /** 表示をすべて消し、常駐をやめる (通知の「停止」や br stop から) */
    static void stopAll(Context c) {
        Control self;
        synchronized (Control.class) {
            self = instance;
        }
        if (self != null) self.onUi(self::clearOverlays);
        KeepAliveService.stop(c);
    }

    static final String HELP = "br: プログラムから Bionic Runner の機能を使うコマンド\n"
            + "\n"
            + "画面の上に表示 (ほかのアプリを使っていても見える)\n"
            + "  br overlay set 名前 文字 [--x N] [--y N] [--size N] [--color #RRGGBB] [--bg #AARRGGBB]\n"
            + "                         名前ごとに 1 つ。同じ名前でもう一度呼ぶと中身が変わる\n"
            + "  br overlay hide 名前   消す        br overlay clear   全部消す\n"
            + "\n"
            + "画面の読み取りと操作 (ユーザー補助を有効にしてあること)\n"
            + "  br ui dump             画面の部品の一覧 (位置 / 押せるか / 種類 / 文字 / 説明 / 名前)\n"
            + "  br ui find 文字...     その文字を含む部品を探す (無ければ終了コード 1)\n"
            + "  br ui click 文字...    その文字を含む部品を押す (先に書いたものを優先。--exact で完全一致)\n"
            + "  br ui app              いま前面にあるアプリのパッケージ名\n"
            + "  br tap X Y             画面をタップ\n"
            + "  br swipe X1 Y1 X2 Y2 [ミリ秒]\n"
            + "  br key back|home|recents|notifications\n"
            + "\n"
            + "そのほか\n"
            + "  br net                 通信量と、前回呼んでからの速度 (rx_bytes_per_sec など)、Wi-Fi の状態\n"
            + "  br kill パッケージ名   アプリを強制終了\n"
            + "  br stop                表示を全部消して、常駐をやめる\n";

    @Override
    public String handle(String[] a) throws ControlServer.Refused {
        if (a.length == 0 || a[0].equals("help") || a[0].equals("--help") || a[0].equals("-h")) return HELP;
        if (!Prefs.controlEnabled(app)) {
            throw new ControlServer.Refused("この機能はオフになっています。Bionic Runner の設定 → 個人設定 →"
                    + "「自動操作と画面上の表示」をオンにしてください");
        }
        // ほかのアプリを前面にしても動き続けられるよう、常駐を始める
        if (!a[0].equals("stop")) KeepAliveService.ensureStarted(app);
        switch (a[0]) {
            case "overlay":
                return overlay(a);
            case "ui":
                return uiCommand(a);
            case "tap":
                need(a, 3, "br tap X Y");
                return gesture("input tap " + num(a[1]) + " " + num(a[2]),
                        s -> s.tap(num(a[1]), num(a[2])));
            case "swipe": {
                need(a, 5, "br swipe X1 Y1 X2 Y2 [ミリ秒]");
                final int ms = a.length > 5 ? num(a[5]) : 300;
                return gesture("input swipe " + num(a[1]) + " " + num(a[2]) + " " + num(a[3]) + " "
                        + num(a[4]) + " " + ms, s -> s.swipe(num(a[1]), num(a[2]), num(a[3]), num(a[4]), ms));
            }
            case "key": {
                need(a, 2, "br key back|home|recents|notifications");
                String code = a[1].equals("back") ? "4" : a[1].equals("home") ? "3"
                        : a[1].equals("recents") ? "187" : a[1].equals("notifications") ? "83" : null;
                if (code == null) throw new ControlServer.Refused("使えるのは back / home / recents / notifications です");
                return gesture("input keyevent " + code, s -> s.key(a[1]));
            }
            case "net":
                return net();
            case "kill":
                need(a, 2, "br kill パッケージ名");
                return kill(a[1]);
            case "stop":
                stopAll(app);
                return "停止しました";
            default:
                throw new ControlServer.Refused("知らない命令です: " + a[0] + " (br help で一覧)");
        }
    }

    private static void need(String[] a, int count, String usage) throws ControlServer.Refused {
        if (a.length < count) throw new ControlServer.Refused("使い方: " + usage);
    }

    private static int num(String s) throws ControlServer.Refused {
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            throw new ControlServer.Refused("数ではありません: " + s);
        }
    }

    // ------------------------------------------------------------ 画面の上に表示

    private String overlay(final String[] a) throws ControlServer.Refused {
        need(a, 2, "br overlay set 名前 文字 / br overlay hide 名前 / br overlay clear");
        if (!Settings.canDrawOverlays(app)) {
            throw new ControlServer.Refused("「他のアプリの上に重ねて表示」の許可がありません。Bionic Runner の"
                    + "設定 → 個人設定 →「自動操作と画面上の表示」から許可してください");
        }
        switch (a[1]) {
            case "clear":
                onUi(this::clearOverlays);
                return "";
            case "hide":
                need(a, 3, "br overlay hide 名前");
                onUi(() -> removeOverlay(a[2]));
                return "";
            case "set": {
                need(a, 4, "br overlay set 名前 文字 [--x N] [--y N] [--size N] [--color #RRGGBB] [--bg #AARRGGBB]");
                final String name = a[2], text = a[3];
                Integer x = null, y = null, size = null, color = null, bg = null;
                for (int i = 4; i + 1 < a.length; i += 2) {
                    switch (a[i]) {
                        case "--x": x = num(a[i + 1]); break;
                        case "--y": y = num(a[i + 1]); break;
                        case "--size": size = num(a[i + 1]); break;
                        case "--color": color = color(a[i + 1]); break;
                        case "--bg": bg = color(a[i + 1]); break;
                        default: throw new ControlServer.Refused("知らない指定です: " + a[i]);
                    }
                }
                final Integer fx = x, fy = y, fs = size, fc = color, fb = bg;
                final String[] error = {null};
                onUi(() -> {
                    try {
                        setOverlay(name, text, fx, fy, fs, fc, fb);
                    } catch (RuntimeException e) {
                        error[0] = e.getMessage();
                    }
                });
                if (error[0] != null) throw new ControlServer.Refused("表示できませんでした: " + error[0]);
                return "";
            }
            default:
                throw new ControlServer.Refused("使えるのは set / hide / clear です");
        }
    }

    private static int color(String s) throws ControlServer.Refused {
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            throw new ControlServer.Refused("色は #RRGGBB か #AARRGGBB の形で書いてください: " + s);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics()));
    }

    /** UI スレッドで呼ぶ。無ければ作り、あれば中身と見た目を変える。null の指定は「今のまま」 */
    private void setOverlay(String name, String text, Integer x, Integer y, Integer size,
                            Integer color, Integer bg) {
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        TextView view = overlays.get(name);
        boolean fresh = view == null;
        WindowManager.LayoutParams lp;
        if (fresh) {
            if (overlays.size() >= 16) throw new IllegalStateException("表示は 16 個までです");
            view = new TextView(app);
            view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            view.setTextSize(13);
            view.setTextColor(Color.WHITE);
            view.setPadding(dp(8), dp(3), dp(8), dp(3));
            view.setTag(0xB3000000); // 背景色を覚えておく
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // 触れない窓にする: 下にあるアプリの操作を邪魔しない
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(8);
            // 既定の位置は左上。2 個目以降は重ならないよう下へずらす
            lp.y = dp(36) + overlays.size() * dp(26);
        } else {
            lp = (WindowManager.LayoutParams) view.getLayoutParams();
        }
        view.setText(text);
        if (size != null) view.setTextSize(Math.max(6, Math.min(size, 60)));
        if (color != null) view.setTextColor(color);
        if (bg != null) view.setTag(bg);
        GradientDrawable back = new GradientDrawable();
        back.setColor((Integer) view.getTag());
        back.setCornerRadius(dp(6));
        view.setBackground(back);
        if (x != null) lp.x = x;
        if (y != null) lp.y = y;
        if (fresh) {
            wm.addView(view, lp);
            overlays.put(name, view);
        } else {
            wm.updateViewLayout(view, lp);
        }
    }

    private void removeOverlay(String name) {
        TextView v = overlays.remove(name);
        if (v == null) return;
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (RuntimeException ignored) {
            // 既に外れている
        }
    }

    private void clearOverlays() {
        for (String name : overlays.keySet().toArray(new String[0])) removeOverlay(name);
    }

    /** UI スレッドで実行し、終わるまで待つ */
    private void onUi(final Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        ui.post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 画面の読み取りと操作

    private interface Action {
        boolean run(BrAccessibilityService s) throws ControlServer.Refused;
    }

    private static final String NEED_A11Y = "画面の読み取りと操作には、ユーザー補助を有効にする必要があります。"
            + "Bionic Runner の設定 → 個人設定 →「自動操作と画面上の表示」から有効にしてください";

    /**
     * タップなどの操作。ユーザー補助が有効ならそれで行い、無効でも root が使えれば
     * Android の input コマンドで行う。どちらも無ければ断る。
     */
    private String gesture(String rootCommand, Action action) throws ControlServer.Refused {
        BrAccessibilityService s = BrAccessibilityService.get();
        if (s != null) {
            if (action.run(s)) return "";
            throw new ControlServer.Refused("操作できませんでした");
        }
        if (root(rootCommand)) return "";
        throw new ControlServer.Refused(NEED_A11Y + " (root が使える端末では、無効のままでもタップなどは動きます)");
    }

    private String uiCommand(String[] a) throws ControlServer.Refused {
        need(a, 2, "br ui dump / find 文字... / click 文字... / app");
        BrAccessibilityService s = BrAccessibilityService.get();
        if (s == null) throw new ControlServer.Refused(NEED_A11Y);
        boolean exact = false;
        String[] words = new String[0];
        for (int i = 2; i < a.length; i++) {
            if (a[i].equals("--exact")) {
                exact = true;
            } else {
                words = Arrays.copyOf(words, words.length + 1);
                words[words.length - 1] = a[i];
            }
        }
        switch (a[1]) {
            case "app":
                return s.foregroundPackage();
            case "dump":
                return lines(s.nodes(new String[0], false));
            case "find": {
                if (words.length == 0) throw new ControlServer.Refused("使い方: br ui find 文字...");
                List<BrAccessibilityService.Node> found = s.nodes(words, exact);
                if (found.isEmpty()) throw new ControlServer.Refused("見つかりません");
                return lines(found);
            }
            case "click": {
                if (words.length == 0) throw new ControlServer.Refused("使い方: br ui click 文字...");
                // 先に書いた文字を優先して探す (「閉じる」が無ければ「×」、のように並べられる)
                for (String w : words) {
                    List<BrAccessibilityService.Node> found = s.nodes(new String[]{w}, exact);
                    if (found.isEmpty()) continue;
                    // 押せる部品を優先する
                    BrAccessibilityService.Node target = found.get(0);
                    for (BrAccessibilityService.Node n : found) {
                        if (n.clickable) {
                            target = n;
                            break;
                        }
                    }
                    if (s.click(target)) return target.line();
                    throw new ControlServer.Refused("押せませんでした: " + target.line());
                }
                throw new ControlServer.Refused("見つかりません");
            }
            default:
                throw new ControlServer.Refused("使えるのは dump / find / click / app です");
        }
    }

    private static String lines(List<BrAccessibilityService.Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (BrAccessibilityService.Node n : nodes) sb.append(n.line()).append('\n');
        return sb.toString();
    }

    // -------------------------------------------------------------- 通信量と強制終了

    // 前回 br net が呼ばれたときの値 (速度の計算用)
    private long lastRx = -1, lastTx = -1, lastTime;

    /**
     * 通信量 (累計のバイト数) と、前回この命令が呼ばれてからの平均の速度 (1 秒あたりのバイト数)。
     * 1 秒おきに呼べば、その 1 秒間の速度になる。最初の 1 回は速度 0。
     */
    private synchronized String net() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        long rxRate = 0, txRate = 0;
        if (lastRx >= 0 && now > lastTime && rx >= lastRx && tx >= lastTx) {
            rxRate = (rx - lastRx) * 1000 / (now - lastTime);
            txRate = (tx - lastTx) * 1000 / (now - lastTime);
        }
        lastRx = rx;
        lastTx = tx;
        lastTime = now;
        long mrx = TrafficStats.getMobileRxBytes(), mtx = TrafficStats.getMobileTxBytes();
        StringBuilder sb = new StringBuilder();
        sb.append("time_ms=").append(now).append('\n')
                .append("rx_bytes_per_sec=").append(rxRate).append('\n')
                .append("tx_bytes_per_sec=").append(txRate).append('\n')
                .append("rx_bytes=").append(Math.max(0, rx)).append('\n')
                .append("tx_bytes=").append(Math.max(0, tx)).append('\n')
                .append("mobile_rx_bytes=").append(Math.max(0, mrx)).append('\n')
                .append("mobile_tx_bytes=").append(Math.max(0, mtx)).append('\n')
                .append("wifi_rx_bytes=").append(Math.max(0, rx - Math.max(0, mrx))).append('\n')
                .append("wifi_tx_bytes=").append(Math.max(0, tx - Math.max(0, mtx))).append('\n');
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            boolean on = info != null && info.getNetworkId() != -1;
            sb.append("wifi_connected=").append(on ? 1 : 0).append('\n');
            if (on) {
                sb.append("wifi_link_mbps=").append(info.getLinkSpeed()).append('\n')
                        .append("wifi_rssi_dbm=").append(info.getRssi()).append('\n');
            }
        } catch (RuntimeException e) {
            sb.append("wifi_connected=0\n");
        }
        return sb.toString();
    }

    /** アプリを強制終了する。root があれば完全に、無ければ裏で動いている分だけ */
    private String kill(String pkg) throws ControlServer.Refused {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new ControlServer.Refused("パッケージ名ではありません: " + pkg);
        if (pkg.equals(app.getPackageName())) throw new ControlServer.Refused("Bionic Runner 自身は終了できません");
        if (root("am force-stop " + pkg)) return "強制終了しました: " + pkg;
        try {
            ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
        } catch (RuntimeException e) {
            throw new ControlServer.Refused("終了できませんでした: " + e.getMessage());
        }
        return "root が使えないため、裏で動いている分だけ終了させました: " + pkg
                + " (前面のアプリや常駐サービスまで止める完全な強制終了には root が必要です)";
    }

    /** root でコマンドを 1 つ実行する。成功したら真。root が無い・断られた・失敗した、は偽 */
    static boolean root(String command) {
        try {
            Process p = new ProcessBuilder(RootCheck.su(), "-c", command)
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] buf = new byte[4096];
            while (p.getInputStream().read(buf) > 0) {
                // 出力は使わない
            }
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
