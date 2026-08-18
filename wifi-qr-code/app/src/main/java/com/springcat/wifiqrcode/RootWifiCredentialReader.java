package com.springcat.wifiqrcode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * root化端末でのみ動作する、保存済みWi-Fi設定(WifiConfigStore.xml)からのパスワード読み取り。
 * root権限が無い端末では常にnullを返し、呼び出し側は手入力にフォールバックする。
 *
 * 自分自身の端末・自分自身が保有するroot権限で、自分が設定したWi-Fiのパスワードを読むだけであり、
 * 他者の端末やネットワークへの不正アクセスは行わない。
 */
final class RootWifiCredentialReader {

    // Android のバージョンやOEMによって保存場所が異なるため候補を複数試す。
    private static final String[] CANDIDATE_PATHS = {
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml", // Android 12+ (Wi-Fi Mainlineモジュール)
            "/data/misc/wifi/WifiConfigStore.xml",                       // Android 10-11
            "/data/misc/wifi/wpa_supplicant.conf",                       // 古い端末向けフォールバック
    };

    private RootWifiCredentialReader() {
    }

    static boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            return code == 0 && out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** 指定SSIDの保存済みパスワードをroot権限で取得を試みる。取得できなければnull。 */
    static String tryReadPassword(String ssid) {
        if (ssid == null || ssid.isEmpty()) return null;
        for (String path : CANDIDATE_PATHS) {
            String content = readFileAsRoot(path);
            if (content == null || content.isEmpty()) continue;
            String pass = path.endsWith(".conf")
                    ? extractFromSupplicantConf(content, ssid)
                    : extractFromConfigStoreXml(content, ssid);
            if (pass != null) return pass;
        }
        return null;
    }

    private static String readFileAsRoot(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat '" + path + "'"});
            String out = readAll(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** WifiConfigStore.xml から <Network>...</Network> 単位でSSID一致を探し、PreSharedKeyを抜き出す。 */
    private static String extractFromConfigStoreXml(String xml, String targetSsid) {
        Pattern networkBlock = Pattern.compile("<Network>(.*?)</Network>", Pattern.DOTALL);
        Matcher blocks = networkBlock.matcher(xml);
        while (blocks.find()) {
            String block = blocks.group(1);
            String ssid = extractQuoted(block, "SSID");
            if (ssid == null || !ssid.equals(targetSsid)) continue;

            String psk = extractQuoted(block, "PreSharedKey");
            if (psk != null && !psk.isEmpty() && !psk.equals("*")) {
                return psk;
            }
            // WEPの場合は WEPKeys 配列の1件目 (キーがあれば) を使う
            Matcher wep = Pattern.compile("<string-array name=\"WEPKeys\"[^>]*>(.*?)</string-array>", Pattern.DOTALL).matcher(block);
            if (wep.find()) {
                Matcher item = Pattern.compile("<item value=\"(.*?)\"").matcher(wep.group(1));
                if (item.find() && !item.group(1).isEmpty()) {
                    return item.group(1);
                }
            }
            // オープンネットワーク (パスワード無し)
            return "";
        }
        return null;
    }

    private static String extractQuoted(String block, String tagName) {
        Matcher m = Pattern.compile("<string name=\"" + tagName + "\">(.*?)</string>", Pattern.DOTALL).matcher(block);
        if (!m.find()) return null;
        String value = m.group(1);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return unescapeXml(value);
    }

    private static String extractFromSupplicantConf(String conf, String targetSsid) {
        Pattern block = Pattern.compile("network=\\{(.*?)\\}", Pattern.DOTALL);
        Matcher m = block.matcher(conf);
        while (m.find()) {
            String netBlock = m.group(1);
            Matcher ssidM = Pattern.compile("ssid=\"(.*?)\"").matcher(netBlock);
            if (!ssidM.find() || !ssidM.group(1).equals(targetSsid)) continue;
            Matcher pskM = Pattern.compile("psk=\"(.*?)\"").matcher(netBlock);
            if (pskM.find()) return pskM.group(1);
            return "";
        }
        return null;
    }

    private static String unescapeXml(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
