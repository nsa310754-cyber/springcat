package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;

/** root が使えるかの確認結果を、原因と直し方つきで利用者に見せる */
final class RootUi {

    /** root 管理アプリ: {パッケージ名, 表示名, 確認画面を出す方式か} */
    private static final String[][] MANAGERS = {
            {"com.topjohnwu.magisk", "Magisk", "prompt"},
            {"io.github.vvb2060.magisk", "Magisk Alpha", "prompt"},
            {"io.github.huskydg.magisk", "Kitsune Magisk", "prompt"},
            {"eu.chainfire.supersu", "SuperSU", "prompt"},
            {"me.weishu.kernelsu", "KernelSU", "list"},
            {"com.rifsxd.ksunext", "KernelSU Next", "list"},
            {"com.sukisu.ultra", "SukiSU Ultra", "list"},
            {"me.bmax.apatch", "APatch", "list"},
    };

    private RootUi() {
    }

    /** 入っている root 管理アプリ {パッケージ名, 表示名, 方式}。見つからなければ null */
    static String[] manager(Activity a) {
        PackageManager pm = a.getPackageManager();
        for (String[] m : MANAGERS) {
            try {
                pm.getPackageInfo(m[0], 0);
                return m;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 次の候補へ
            }
        }
        return null;
    }

    /** 状態を 1 行で (コマンドプロンプトの出力やタスクバー用) */
    static String summary(RootCheck.Result r) {
        switch (r.state) {
            case GRANTED:
                return "root 権限を使えます";
            case DENIED:
                return "su はありますが、root 権限が許可されていません";
            default:
                return "su が見つかりません (root 化されていないか、このアプリに許可されていません)";
        }
    }

    private static String explain(RootCheck.Result r, String[] manager) {
        String name = manager == null ? null : manager[1];
        boolean listStyle = manager != null && manager[2].equals("list");
        StringBuilder sb = new StringBuilder(summary(r)).append("。\n\n");
        if (r.state == RootCheck.State.GRANTED) {
            return sb.append("su の場所: ").append(r.suPath).toString();
        }
        if (r.suPath != null) sb.append("su の場所: ").append(r.suPath).append('\n');
        if (!r.output.isEmpty()) sb.append("su の応答: ").append(r.output).append('\n');
        sb.append('\n');

        if (listStyle) {
            sb.append(name).append(" が入っています。").append(name)
                    .append(" は確認画面を出さない方式です。\n")
                    .append("「管理アプリを開く」→「スーパーユーザー」の一覧で Bionic Runner を"
                            + "オンにしてから、「もう一度確認」を押してください。");
        } else if (name != null) {
            sb.append(name).append(" が入っています。\n");
            if (r.state == RootCheck.State.DENIED) {
                sb.append("以前に「拒否」を選んだか、確認画面が時間切れになると、次からは確認画面が"
                        + "出ずに拒否されます。\n「管理アプリを開く」→「スーパーユーザー」の一覧で "
                        + "Bionic Runner をオンにしてから、「もう一度確認」を押してください。\n"
                        + "一覧に無い場合は、").append(name)
                        .append(" の設定で「スーパーユーザーアクセス」が有効か、"
                                + "「自動応答」が「拒否」になっていないかを確認してください。");
            } else {
                sb.append("管理アプリはありますが su が見えません。").append(name)
                        .append(" を開いて、インストール状態 (「インストール済み」にバージョンが出ているか)"
                                + " と「スーパーユーザーアクセス」の設定を確認してください。");
            }
        } else {
            sb.append("root 管理アプリが見つかりません。考えられる原因:\n"
                    + "・端末が root 化されていない (このアプリは端末を root 化しません)\n"
                    + "・Magisk アプリを別名に偽装している (その場合は Magisk を開いて"
                    + "「スーパーユーザー」で Bionic Runner をオンにしてください)\n"
                    + "・KernelSU / APatch を使っている (確認画面は出ません。管理アプリの"
                    + "「スーパーユーザー」で許可が必要です)");
        }
        sb.append("\n\nroot が無い間は制限モードで動きます (APK は標準インストーラ経由、"
                + "glibc 用プログラムと絵・音の出るゲームは実行不可)。");
        return sb.toString();
    }

    /** 結果を表示する。「もう一度確認」を押すと recheck を呼ぶ */
    static void show(final Activity a, RootCheck.Result r, final Runnable recheck) {
        if (a.isFinishing()) return;
        final String[] manager = manager(a);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("スーパーユーザー権限")
                .setMessage(explain(r, manager))
                .setNegativeButton("閉じる", null);
        if (r.state != RootCheck.State.GRANTED) {
            b.setPositiveButton("もう一度確認", (d, w) -> recheck.run());
            if (manager != null) {
                b.setNeutralButton("管理アプリを開く", (d, w) -> {
                    Intent i = a.getPackageManager().getLaunchIntentForPackage(manager[0]);
                    if (i != null) a.startActivity(i);
                });
            }
        }
        b.show();
    }
}
