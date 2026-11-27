package site.springcat.bionicrunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * デスクトップのアイコンの並び順とフォルダ分け。画面とは切り離した「並びの表」だけを扱う。
 *
 * 表は上から順の一覧で、1 項目はアイコン 1 個か、アイコンを入れたフォルダ 1 個。
 * アイコンは変わらない名前 (id) で指す: 組み込みのものは "b:種類"、登録した実行ファイルは "x:名前"。
 * 保存は 1 行 1 項目の文字列 (区切りはタブ)。Android の API は使っていないので、
 * PC 上の JVM でも単体で検証できる。
 */
final class DesktopLayout {

    /** 表の 1 項目。children が null ならアイコン、そうでなければフォルダ */
    static final class Node {
        final String id;
        /** フォルダの名前 (アイコンでは使わない) */
        String name;
        final List<String> children;

        Node(String id) {
            this.id = id;
            this.children = null;
        }

        Node(String id, String name, List<String> children) {
            this.id = id;
            this.name = name;
            this.children = children;
        }

        boolean isFolder() {
            return children != null;
        }
    }

    private DesktopLayout() {
    }

    // ------------------------------------------------------------ 保存と読み込み

    static String format(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            if (n.isFolder()) {
                sb.append("F\t").append(clean(n.id)).append('\t').append(clean(n.name));
                for (String c : n.children) sb.append('\t').append(clean(c));
            } else {
                sb.append("I\t").append(clean(n.id));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static List<Node> parse(String text) {
        List<Node> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && f[0].equals("I") && !f[1].isEmpty()) {
                out.add(new Node(f[1]));
            } else if (f.length >= 3 && f[0].equals("F") && !f[1].isEmpty()) {
                List<String> kids = new ArrayList<>();
                for (int i = 3; i < f.length; i++) if (!f[i].isEmpty()) kids.add(f[i]);
                out.add(new Node(f[1], f[2], kids));
            }
        }
        return out;
    }

    /** 区切りに使っている文字 (タブ・改行) を名前から取り除く */
    static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ------------------------------------------------------------ 実際の項目との突き合わせ

    /**
     * 保存してあった表を、いま実際にあるアイコン (existing。既定の並び順) に合わせる。
     * 無くなったもの (アンインストールした実行ファイルなど) は表から外し、新しく増えたものは
     * 末尾に足す。中身が 1 個以下になったフォルダは解除する。
     */
    static List<Node> reconcile(List<Node> saved, List<String> existing) {
        Set<String> alive = new HashSet<>(existing);
        Set<String> placed = new HashSet<>();
        List<Node> out = new ArrayList<>();
        for (Node n : saved) {
            if (n.isFolder()) {
                List<String> kids = new ArrayList<>();
                for (String c : n.children) if (alive.contains(c) && placed.add(c)) kids.add(c);
                if (kids.size() >= 2) out.add(new Node(n.id, n.name, kids));
                else if (kids.size() == 1) out.add(new Node(kids.get(0)));
            } else if (alive.contains(n.id) && placed.add(n.id)) {
                out.add(new Node(n.id));
            }
        }
        for (String id : existing) if (placed.add(id)) out.add(new Node(id));
        return out;
    }

    // ------------------------------------------------------------------ 操作

    /**
     * from 番目の項目を、to 番目の項目の手前へ動かす (to が項目数以上なら末尾へ)。
     * 番号はどちらも動かす前の並びでのもの。
     */
    static void move(List<Node> nodes, int from, int to) {
        if (from < 0 || from >= nodes.size()) return;
        to = Math.max(0, Math.min(to, nodes.size()));
        if (to == from || to == from + 1) return; // 同じ場所
        Node n = nodes.remove(from);
        nodes.add(to > from ? to - 1 : to, n);
    }

    /**
     * from 番目のアイコンを onto 番目の項目に重ねる。相手がアイコンなら、2 つを入れた新しい
     * フォルダ (id は folderId、名前は folderName) を相手の場所に作る。相手がフォルダなら、その中に入れる。
     * フォルダを何かに重ねることはできない (何もしないで false)。
     */
    static boolean merge(List<Node> nodes, int from, int onto, String folderId, String folderName) {
        if (from == onto || from < 0 || onto < 0 || from >= nodes.size() || onto >= nodes.size()) {
            return false;
        }
        Node moved = nodes.get(from), target = nodes.get(onto);
        if (moved.isFolder()) return false;
        if (target.isFolder()) {
            target.children.add(moved.id);
        } else {
            List<String> kids = new ArrayList<>();
            kids.add(target.id);
            kids.add(moved.id);
            nodes.set(onto, new Node(folderId, folderName, kids));
        }
        nodes.remove(from);
        return true;
    }

    /**
     * index 番目のフォルダから childId を取り出し、フォルダのすぐ後ろに置く。
     * 中身が 1 個になったフォルダは解除して、残った 1 個をその場所に置く。
     */
    static void takeOut(List<Node> nodes, int index, String childId) {
        if (index < 0 || index >= nodes.size()) return;
        Node folder = nodes.get(index);
        if (!folder.isFolder() || !folder.children.remove(childId)) return;
        nodes.add(index + 1, new Node(childId));
        if (folder.children.size() == 1) nodes.set(index, new Node(folder.children.get(0)));
        else if (folder.children.isEmpty()) nodes.remove(index);
    }

    /** index 番目のフォルダを解除し、中身をその場所に並べる */
    static void dissolve(List<Node> nodes, int index) {
        if (index < 0 || index >= nodes.size() || !nodes.get(index).isFolder()) return;
        Node folder = nodes.remove(index);
        int at = index;
        for (String c : folder.children) nodes.add(at++, new Node(c));
    }

    /**
     * ids のアイコンを 1 つのフォルダ (名前 name) にまとめる。アイコンがデスクトップや別のフォルダに
     * あっても取り出して入れる。フォルダは、まとめるアイコンのうち最初に見つかったものの場所に置く。
     * 表にあるものが 2 個未満なら何もしないで false
     */
    static boolean group(List<Node> nodes, String folderId, String name, List<String> ids) {
        Set<String> want = new HashSet<>(ids);
        List<String> kids = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n.isFolder()) {
                for (String c : n.children) if (want.contains(c)) kids.add(c);
            } else if (want.contains(n.id)) {
                kids.add(n.id);
                if (at < 0) at = i;
            }
        }
        if (kids.size() < 2) return false;
        // 元の並び (ids の順) にそろえる
        List<String> ordered = new ArrayList<>();
        for (String id : ids) if (kids.contains(id) && !ordered.contains(id)) ordered.add(id);
        int removedBefore = 0;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node n = nodes.get(i);
            if (n.isFolder()) {
                n.children.removeAll(want);
                if (n.children.size() == 1) nodes.set(i, new Node(n.children.get(0)));
                else if (n.children.isEmpty()) {
                    nodes.remove(i);
                    if (at >= 0 && i < at) removedBefore++;
                }
            } else if (want.contains(n.id)) {
                nodes.remove(i);
                if (at >= 0 && i < at) removedBefore++;
            }
        }
        int pos = at < 0 ? nodes.size() : Math.min(at - removedBefore, nodes.size());
        nodes.add(pos, new Node(folderId, clean(name).isEmpty() ? "フォルダ" : clean(name), ordered));
        return true;
    }

    /** まだ使われていないフォルダの id を作る */
    static String newFolderId(List<Node> nodes) {
        Set<String> used = new HashSet<>();
        for (Node n : nodes) used.add(n.id);
        for (int i = 1; ; i++) {
            if (!used.contains("f:" + i)) return "f:" + i;
        }
    }
}
