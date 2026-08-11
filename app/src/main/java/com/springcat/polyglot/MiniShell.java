package com.springcat.polyglot;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A tiny on-device shell over a persistent workspace folder. It is NOT a real
 * system shell: there is no process execution, no package manager and no network
 * package install. What it genuinely supports is file wrangling — uploading
 * files, extracting archives (zip / tar / tar.gz / gz) and the common file
 * commands — all implemented with the Java standard library so it works offline.
 *
 * Pure Java (no Android imports) so it can be unit-tested off-device.
 */
public final class MiniShell {

    private final File root;
    private File cwd;

    public MiniShell(File root) {
        this.root = root;
        this.cwd = root;
        root.mkdirs();
    }

    public File root() { return root; }
    public File cwd() { return cwd; }

    /** Save raw bytes as a file in the current directory (used by file upload). */
    public File save(String name, byte[] data) throws IOException {
        File f = new File(cwd, sanitizeName(name));
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    /** Run a script (one command per line) and return the combined output. */
    public String run(String script) {
        StringBuilder out = new StringBuilder();
        for (String raw : script.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                String r = exec(line);
                if (r != null && !r.isEmpty()) {
                    out.append(r);
                    if (!r.endsWith("\n")) out.append('\n');
                }
            } catch (ShellError e) {
                out.append(e.getMessage()).append('\n');
            } catch (Exception e) {
                out.append("error: ").append(e).append('\n');
            }
        }
        if (out.length() == 0) return "(ok)";
        return out.toString();
    }

    // ------------------------------------------------------------ dispatch --

    private static final class ShellError extends RuntimeException {
        ShellError(String m) { super(m); }
    }

    private String exec(String line) throws IOException {
        // Redirection: ... > file  or  ... >> file
        String redirectFile = null;
        boolean append = false;
        List<String> tok = tokenize(line);
        for (int i = 0; i < tok.size(); i++) {
            if (tok.get(i).equals(">") || tok.get(i).equals(">>")) {
                append = tok.get(i).equals(">>");
                if (i + 1 >= tok.size()) throw new ShellError("syntax: missing file after " + tok.get(i));
                redirectFile = tok.get(i + 1);
                tok = new ArrayList<>(tok.subList(0, i));
                break;
            }
        }
        if (tok.isEmpty()) return "";
        String cmd = tok.get(0);
        List<String> args = tok.subList(1, tok.size());

        String result;
        switch (cmd) {
            case "help":     result = help(); break;
            case "pwd":      result = rel(cwd); break;
            case "ls":       result = ls(args); break;
            case "cd":       cd(args); result = ""; break;
            case "mkdir":    mkdir(args); result = ""; break;
            case "rm":       rm(args); result = ""; break;
            case "mv":       mv(args); result = ""; break;
            case "cp":       cp(args); result = ""; break;
            case "cat":      result = cat(args); break;
            case "echo":     result = String.join(" ", args); break;
            case "touch":    touch(args); result = ""; break;
            case "wc":       result = wc(args); break;
            case "head":     result = headTail(args, true); break;
            case "tail":     result = headTail(args, false); break;
            case "find":     result = find(args); break;
            case "tree":     result = tree(args); break;
            case "unzip":    result = unzip(args); break;
            case "tar":      result = tar(args); break;
            case "untar":    result = tar(prepend("-xf", args)); break;
            case "gunzip":   result = gunzip(args); break;
            case "download": result = download(args); break;
            case "clear":    result = clearWorkspace(); break;
            case "npm": case "npx": case "pnpm": case "yarn":
            case "pip": case "pip3": case "apt": case "apt-get":
            case "brew": case "gem": case "cargo": case "go":
            case "make": case "gcc": case "python": case "node":
                result = unsupported(cmd); break;
            default:
                throw new ShellError(cmd + ": command not found (try 'help')");
        }
        if (redirectFile != null) {
            writeFile(resolve(redirectFile), result, append);
            return "";
        }
        return result;
    }

    // ------------------------------------------------------------ commands --

    private String help() {
        return "Files persist in an on-device workspace. Upload files with the 📁 button,\n"
                + "then extract/inspect them here. Available commands:\n"
                + "  ls [-l] [path]      pwd    cd <dir>    tree [path]    find [path]\n"
                + "  mkdir [-p] <dir>    rm [-r] <path>     mv <a> <b>     cp [-r] <a> <b>\n"
                + "  cat <file...>       echo <text> [> file]   touch <file>   wc <file>\n"
                + "  head [-n N] <file>  tail [-n N] <file>\n"
                + "  unzip <f.zip> [-d dir]     tar -xf|-xzf <f.tar[.gz]> [-C dir]\n"
                + "  gunzip <f.gz>       download <url> [name]      clear\n"
                + "Not available on-device: npm, pip, apt, node, gcc, … (see why by running one).";
    }

    private String unsupported(String cmd) {
        return cmd + ": not available on-device.\n"
                + "This shell has no process runtime, package registry, or install sandbox,\n"
                + "so package managers/compilers can't run here. What you CAN do:\n"
                + "  • upload a prebuilt archive and `unzip` / `tar -xf` it, then inspect files\n"
                + "  • run source code with the language pickers (they execute remotely/offline)";
    }

    private String ls(List<String> args) {
        boolean longFmt = args.contains("-l");
        String path = firstNonFlag(args);
        File dir = path == null ? cwd : resolve(path);
        if (!dir.exists()) throw new ShellError("ls: " + path + ": no such file or directory");
        if (dir.isFile()) return dir.getName();
        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) return "(empty)";
        Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        StringBuilder sb = new StringBuilder();
        for (File f : kids) {
            if (longFmt) {
                sb.append(f.isDirectory() ? "d " : "- ")
                        .append(pad(f.isDirectory() ? "-" : String.valueOf(f.length()), 9))
                        .append("  ");
            }
            sb.append(f.getName()).append(f.isDirectory() ? "/" : "").append('\n');
        }
        return sb.toString().trim();
    }

    private void cd(List<String> args) {
        String p = args.isEmpty() ? "/" : args.get(0);
        File dir = resolve(p);
        if (!dir.exists() || !dir.isDirectory()) throw new ShellError("cd: " + p + ": not a directory");
        cwd = within(dir);
    }

    private void mkdir(List<String> args) {
        boolean p = args.contains("-p");
        String path = firstNonFlag(args);
        if (path == null) throw new ShellError("mkdir: missing operand");
        File d = within(resolve(path));
        boolean ok = p ? d.mkdirs() : d.mkdir();
        if (!ok && !d.isDirectory()) throw new ShellError("mkdir: cannot create '" + path + "'");
    }

    private void rm(List<String> args) {
        boolean recursive = args.contains("-r") || args.contains("-rf") || args.contains("-fr");
        String path = firstNonFlag(args);
        if (path == null) throw new ShellError("rm: missing operand");
        File f = within(resolve(path));
        if (!f.exists()) throw new ShellError("rm: " + path + ": no such file or directory");
        if (f.isDirectory() && !recursive) throw new ShellError("rm: " + path + ": is a directory (use -r)");
        deleteRecursive(f);
    }

    private void mv(List<String> args) {
        List<String> a = nonFlags(args);
        if (a.size() < 2) throw new ShellError("mv: needs source and destination");
        File src = within(resolve(a.get(0)));
        File dst = within(resolve(a.get(1)));
        if (!src.exists()) throw new ShellError("mv: " + a.get(0) + ": no such file or directory");
        if (dst.isDirectory()) dst = new File(dst, src.getName());
        if (!src.renameTo(dst)) throw new ShellError("mv: failed to move " + a.get(0));
    }

    private void cp(List<String> args) throws IOException {
        boolean recursive = args.contains("-r") || args.contains("-R");
        List<String> a = nonFlags(args);
        if (a.size() < 2) throw new ShellError("cp: needs source and destination");
        File src = within(resolve(a.get(0)));
        File dst = within(resolve(a.get(1)));
        if (!src.exists()) throw new ShellError("cp: " + a.get(0) + ": no such file or directory");
        if (dst.isDirectory()) dst = new File(dst, src.getName());
        copyRecursive(src, dst, recursive);
    }

    private String cat(List<String> args) throws IOException {
        if (args.isEmpty()) throw new ShellError("cat: missing file");
        StringBuilder sb = new StringBuilder();
        for (String p : args) {
            File f = within(resolve(p));
            if (!f.exists() || f.isDirectory()) throw new ShellError("cat: " + p + ": no such file");
            sb.append(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void touch(List<String> args) throws IOException {
        if (args.isEmpty()) throw new ShellError("touch: missing file");
        File f = within(resolve(args.get(0)));
        if (!f.exists()) new FileOutputStream(f).close();
        else f.setLastModified(System.currentTimeMillis());
    }

    private String wc(List<String> args) throws IOException {
        if (args.isEmpty()) throw new ShellError("wc: missing file");
        File f = within(resolve(args.get(0)));
        if (!f.exists() || f.isDirectory()) throw new ShellError("wc: " + args.get(0) + ": no such file");
        String s = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
        int lines = s.isEmpty() ? 0 : s.split("\n", -1).length - (s.endsWith("\n") ? 1 : 0);
        int words = s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
        return lines + " " + words + " " + s.getBytes(StandardCharsets.UTF_8).length + " " + args.get(0);
    }

    private String headTail(List<String> args, boolean head) throws IOException {
        int n = 10;
        List<String> a = new ArrayList<>(args);
        int idx = a.indexOf("-n");
        if (idx >= 0 && idx + 1 < a.size()) { n = Integer.parseInt(a.get(idx + 1)); a.remove(idx + 1); a.remove(idx); }
        String path = a.isEmpty() ? null : a.get(0);
        if (path == null) throw new ShellError((head ? "head" : "tail") + ": missing file");
        File f = within(resolve(path));
        if (!f.exists() || f.isDirectory()) throw new ShellError(path + ": no such file");
        String[] lines = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8).split("\n", -1);
        List<String> ls = new ArrayList<>(Arrays.asList(lines));
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        StringBuilder sb = new StringBuilder();
        if (head) {
            for (int i = 0; i < Math.min(n, ls.size()); i++) sb.append(ls.get(i)).append('\n');
        } else {
            for (int i = Math.max(0, ls.size() - n); i < ls.size(); i++) sb.append(ls.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    private String find(List<String> args) {
        File start = args.isEmpty() ? cwd : within(resolve(args.get(0)));
        StringBuilder sb = new StringBuilder();
        findInto(start, sb);
        return sb.length() == 0 ? "(nothing)" : sb.toString().trim();
    }

    private void findInto(File f, StringBuilder sb) {
        sb.append(rel(f)).append('\n');
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File k : kids) findInto(k, sb);
            }
        }
    }

    private String tree(List<String> args) {
        File start = args.isEmpty() ? cwd : within(resolve(args.get(0)));
        StringBuilder sb = new StringBuilder(rel(start)).append('\n');
        treeInto(start, "", sb);
        return sb.toString().trim();
    }

    private void treeInto(File dir, String prefix, StringBuilder sb) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (int i = 0; i < kids.length; i++) {
            boolean last = i == kids.length - 1;
            sb.append(prefix).append(last ? "└── " : "├── ")
                    .append(kids[i].getName()).append(kids[i].isDirectory() ? "/" : "").append('\n');
            if (kids[i].isDirectory()) treeInto(kids[i], prefix + (last ? "    " : "│   "), sb);
        }
    }

    // ------------------------------------------------------------ archives --

    private String unzip(List<String> args) throws IOException {
        String src = null, destDir = null;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-d") && i + 1 < args.size()) destDir = args.get(++i);
            else if (!args.get(i).startsWith("-")) src = args.get(i);
        }
        if (src == null) throw new ShellError("unzip: missing archive");
        File zip = within(resolve(src));
        if (!zip.exists()) throw new ShellError("unzip: " + src + ": no such file");
        File out = destDir == null ? cwd : within(resolve(destDir));
        out.mkdirs();
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File target = safeChild(out, e.getName());
                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    if (target.getParentFile() != null) target.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) { copy(zis, fos); }
                    count++;
                }
                zis.closeEntry();
            }
        }
        return "unzip: extracted " + count + " file(s) into " + rel(out);
    }

    private String tar(List<String> args) throws IOException {
        boolean gz = false;
        String src = null, destDir = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-C") && i + 1 < args.size()) { destDir = args.get(++i); }
            else if (a.startsWith("-") || a.matches("[xzfvt]+")) { if (a.contains("z")) gz = true; }
            else src = a;
        }
        if (src == null) throw new ShellError("tar: missing archive");
        if (src.endsWith(".tgz") || src.endsWith(".gz")) gz = true;
        File arc = within(resolve(src));
        if (!arc.exists()) throw new ShellError("tar: " + src + ": no such file");
        File out = destDir == null ? cwd : within(resolve(destDir));
        out.mkdirs();
        InputStream in = new FileInputStream(arc);
        if (gz) in = new GZIPInputStream(in);
        int count = extractTar(in, out);
        in.close();
        return "tar: extracted " + count + " file(s) into " + rel(out);
    }

    /** Minimal USTAR reader: regular files and directories. */
    private int extractTar(InputStream in, File out) throws IOException {
        byte[] header = new byte[512];
        int count = 0;
        while (true) {
            if (!readFully(in, header)) break;
            boolean allZero = true;
            for (byte b : header) if (b != 0) { allZero = false; break; }
            if (allZero) break;

            String name = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = parseOctal(header, 124, 12);
            char type = (char) header[156];
            if (name.isEmpty()) { skip(in, padded(size)); continue; }

            File target = safeChild(out, name);
            if (type == '5' || name.endsWith("/")) {
                target.mkdirs();
                skip(in, padded(size));
            } else if (type == '0' || type == 0 || type == '7') {
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    copyN(in, fos, size);
                }
                skip(in, padded(size) - size);
                count++;
            } else {
                skip(in, padded(size)); // symlinks, longname, etc. — skipped
            }
        }
        return count;
    }

    private String gunzip(List<String> args) throws IOException {
        String src = firstNonFlag(args);
        if (src == null) throw new ShellError("gunzip: missing file");
        File gz = within(resolve(src));
        if (!gz.exists()) throw new ShellError("gunzip: " + src + ": no such file");
        String outName = src.endsWith(".gz") ? src.substring(0, src.length() - 3) : src + ".out";
        File out = within(resolve(outName));
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(gz));
             FileOutputStream fos = new FileOutputStream(out)) {
            copy(gis, fos);
        }
        return "gunzip: wrote " + rel(out);
    }

    private String download(List<String> args) throws IOException {
        if (args.isEmpty()) throw new ShellError("download: missing url");
        String url = args.get(0);
        String name = args.size() > 1 ? args.get(1) : url.substring(url.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = "download.bin";
        File out = within(resolve(name));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            copy(in, fos);
        } finally {
            c.disconnect();
        }
        return "download: saved " + rel(out) + " (" + out.length() + " bytes)";
    }

    private String clearWorkspace() {
        File[] kids = root.listFiles();
        if (kids != null) for (File f : kids) deleteRecursive(f);
        cwd = root;
        return "workspace cleared";
    }

    // -------------------------------------------------------------- helpers -

    private File resolve(String p) {
        if (p.equals("/") || p.isEmpty()) return root;
        return p.startsWith("/") ? new File(root, p.substring(1)) : new File(cwd, p);
    }

    /** Ensure a resolved path stays inside the workspace root. */
    private File within(File f) {
        try {
            String rc = root.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (!fc.equals(rc) && !fc.startsWith(rc + File.separator)) {
                throw new ShellError("path escapes the workspace: " + f.getName());
            }
            return f;
        } catch (IOException e) {
            throw new ShellError("bad path: " + e.getMessage());
        }
    }

    /** Guard archive entry paths against zip-slip. */
    private File safeChild(File base, String entryName) {
        File f = new File(base, entryName);
        return within(f);
    }

    private String rel(File f) {
        try {
            String rc = root.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (fc.equals(rc)) return "/";
            if (fc.startsWith(rc + File.separator)) return "/" + fc.substring(rc.length() + 1).replace(File.separatorChar, '/');
            return f.getPath();
        } catch (IOException e) {
            return f.getPath();
        }
    }

    private void writeFile(File f, String content, boolean append) throws IOException {
        within(f);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f, append)) {
            fos.write((content + (content.endsWith("\n") ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private void copyRecursive(File src, File dst, boolean recursive) throws IOException {
        if (src.isDirectory()) {
            if (!recursive) throw new ShellError("cp: " + src.getName() + " is a directory (use -r)");
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()), true);
        } else {
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
        }
    }

    private static List<String> tokenize(String line) {
        List<String> t = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                inQuote = true; quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { t.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) t.add(cur.toString());
        return t;
    }

    private static List<String> prepend(String s, List<String> rest) {
        List<String> a = new ArrayList<>();
        a.add(s);
        a.addAll(rest);
        return a;
    }

    private static String firstNonFlag(List<String> args) {
        for (String a : args) if (!a.startsWith("-")) return a;
        return null;
    }

    private static List<String> nonFlags(List<String> args) {
        List<String> a = new ArrayList<>();
        for (String s : args) if (!s.startsWith("-")) a.add(s);
        return a;
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static String sanitizeName(String name) {
        if (name == null) return "upload.bin";
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name.isEmpty() ? "upload.bin" : name;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.US_ASCII).trim();
    }

    private static long parseOctal(byte[] b, int off, int len) {
        String s = cString(b, off, len).trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { return 0; }
    }

    private static long padded(long size) {
        long rem = size % 512;
        return rem == 0 ? size : size + (512 - rem);
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) return off > 0 ? fill(buf, off) : false;
            off += r;
        }
        return true;
    }

    private static boolean fill(byte[] buf, int from) {
        for (int i = from; i < buf.length; i++) buf[i] = 0;
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) { if (in.read() < 0) return; n--; } else n -= s;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        in.close();
        return bos.toByteArray();
    }
}
