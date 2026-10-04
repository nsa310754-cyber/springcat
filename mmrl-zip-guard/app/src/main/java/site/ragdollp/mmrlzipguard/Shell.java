package site.ragdollp.mmrlzipguard;

import java.io.*;
import java.util.concurrent.TimeUnit;

public class Shell {

    private static Boolean sRootAvailable;

    public static synchronized boolean isRootAvailable() {
        if (sRootAvailable != null) return sRootAvailable;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            sRootAvailable = (line != null && line.contains("uid=0"));
        } catch (Exception e) {
            sRootAvailable = false;
        }
        return sRootAvailable;
    }

    public static void resetRootCache() {
        sRootAvailable = null;
    }

    public static String execRoot(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean copyFile(File src, File dst) {
        if (isRootAvailable() && !src.canRead()) {
            String result = execRoot("cp '" + src.getAbsolutePath() + "' '" + dst.getAbsolutePath() + "'"
                    + " && chmod 644 '" + dst.getAbsolutePath() + "'");
            return result != null && dst.exists();
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String[] listDir(String path) {
        if (isRootAvailable()) {
            String result = execRoot("ls '" + path + "' 2>/dev/null");
            if (result != null && !result.isEmpty()) {
                return result.split("\n");
            }
        }
        File dir = new File(path);
        if (dir.isDirectory()) {
            return dir.list();
        }
        return null;
    }

    public static boolean dirExists(String path) {
        if (isRootAvailable()) {
            String result = execRoot("[ -d '" + path + "' ] && echo yes || echo no");
            return "yes".equals(result);
        }
        return new File(path).isDirectory();
    }
}
