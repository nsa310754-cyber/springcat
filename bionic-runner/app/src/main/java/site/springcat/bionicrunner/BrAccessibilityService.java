package site.springcat.bionicrunner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 自動操作 (br コマンド) のためのユーザー補助サービス。利用者が Android の設定で有効にしたときだけ動く。
 *
 * できること: いま画面に出ているもの (文字・ボタンとその位置) を読む、指定の場所をタップ / スワイプする、
 * 「戻る」「ホーム」などを押す。プログラムから br ui … / br tap … で呼ばれる ({@link Control} 経由)。
 * 自分からは何もせず、画面の内容をどこかへ送ることもしない。
 */
public class BrAccessibilityService extends AccessibilityService {

    /** 画面に出ている部品 1 個 */
    static final class Node {
        final Rect bounds = new Rect();
        boolean clickable;
        String className = "", text = "", desc = "", id = "";
        AccessibilityNodeInfo info;

        /** br ui dump の 1 行: 位置、押せるか、種類、文字、説明、部品の名前 (タブ区切り) */
        String line() {
            return bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + "\t"
                    + (clickable ? "C" : "-") + "\t" + className + "\t" + text + "\t" + desc + "\t" + id;
        }
    }

    private static volatile BrAccessibilityService instance;
    private volatile String foreground = "";

    /** 有効になっていれば、そのサービス。無効なら null */
    static BrAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 前面のアプリが切り替わったら覚えておく (br ui app 用)。ほかのことはしない
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!pkg.equals(getPackageName())) foreground = pkg;
        }
    }

    @Override
    public void onInterrupt() {
    }

    String foregroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && !root.getPackageName().toString().equals(getPackageName())) {
            return root.getPackageName().toString();
        }
        return foreground;
    }

    /**
     * 画面に出ている部品を集める。needles が空でなければ、文字か説明にそのどれかを含むものだけ
     * (exact なら完全に一致するものだけ)。自分のアプリの部品 (画面の上に出している表示など) は除く。
     */
    List<Node> nodes(String[] needles, boolean exact) {
        List<Node> out = new ArrayList<>();
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (RuntimeException ignored) {
            // 窓の一覧を取れない端末では、下の「前面の窓」だけを使う
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        String[] lower = new String[needles.length];
        for (int i = 0; i < needles.length; i++) lower[i] = needles[i].toLowerCase(Locale.ROOT);
        for (AccessibilityNodeInfo r : roots) collect(r, lower, exact, out, 0);
        return out;
    }

    private void collect(AccessibilityNodeInfo n, String[] needles, boolean exact, List<Node> out,
                         int depth) {
        if (n == null || depth > 60 || out.size() >= 4000) return;
        CharSequence pkg = n.getPackageName();
        if (pkg != null && pkg.toString().equals(getPackageName())) return;
        if (n.isVisibleToUser()) {
            Node node = new Node();
            n.getBoundsInScreen(node.bounds);
            node.clickable = n.isClickable();
            node.className = clean(n.getClassName());
            node.text = clean(n.getText());
            node.desc = clean(n.getContentDescription());
            node.id = clean(n.getViewIdResourceName());
            node.info = n;
            boolean hit = needles.length == 0;
            String t = node.text.toLowerCase(Locale.ROOT), d = node.desc.toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (needle.isEmpty()) continue;
                if (exact ? t.equals(needle) || d.equals(needle) : t.contains(needle) || d.contains(needle)) {
                    hit = true;
                    break;
                }
            }
            // 一覧 (絞り込みなし) では、文字も説明も無く押せもしない入れ物は省く
            boolean empty = node.text.isEmpty() && node.desc.isEmpty() && !node.clickable;
            if (hit && !(needles.length == 0 && empty)) out.add(node);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), needles, exact, out, depth + 1);
        }
    }

    private static String clean(CharSequence s) {
        return s == null ? "" : s.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** 部品を押す。押せる部品 (自分か、それを含む親) に「押す」を伝え、だめなら中心をタップする */
    boolean click(Node node) {
        AccessibilityNodeInfo n = node.info;
        for (int i = 0; n != null && i < 8; i++) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            n = n.getParent();
        }
        return tap(node.bounds.centerX(), node.bounds.centerY());
    }

    boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 60);
    }

    boolean swipe(float x1, float y1, float x2, float y2, long millis) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, Math.max(50, Math.min(millis, 10_000)));
    }

    /** 指の動きを 1 つ実行し、終わるまで待つ */
    private boolean gesture(Path path, long millis) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, millis)).build();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                ok[0] = true;
                done.countDown();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                done.countDown();
            }
        }, null);
        if (!sent) return false;
        try {
            done.await(millis + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    /** 「戻る」「ホーム」「アプリの切り替え」「通知」を押す。知らない名前なら false */
    boolean key(String name) {
        switch (name) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: return false;
        }
    }
}
