package site.springcat.bionicrunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Windows のプログラム (PE) の Authenticode の署名を、端末の中で確かめる (ネットにはつながない)。
 *
 * 確かめること:
 *   1. ファイルの中身のハッシュ値 (チェックサムと署名の部分を除く) が、署名の中に書かれた値と一致するか
 *      (署名のあとで書き換えられていないか)
 *   2. 署名の中の「書いた内容のハッシュ値」(messageDigest) が合っているか、そして署名者の証明書の鍵で
 *      署名そのもの (数値) が正しいか
 *   3. 署名者の証明書から、中間の証明書をたどって、指定したルート証明書 (Microsoft のルート) まで、
 *      それぞれの署名が正しくつながっているか
 * 確かめないこと: 証明書の有効期限と、タイムスタンプ (署名した時刻の証明)。コード署名の証明書は短い期間で
 * 期限が切れるので、Windows はタイムスタンプで「署名した時点では有効だった」ことを確かめる。ここではそこまで
 * しないので、期限は見ない (結果にそう書く)。失効 (取り消し) の確認もしない (ネットにつながないため)。
 *
 * Android の機能を使わない (PC の Java でも同じコードを試せるように)。
 */
final class Authenticode {

    static final class Result {
        /** 署名があるか */
        boolean signed;
        /** 署名者の証明書の名前 (CN と O) */
        String signer = "";
        String signerOrg = "";
        /** 1. ファイルの中身が署名と一致 */
        boolean digestOk;
        /** 2. 署名の数値が正しい */
        boolean signatureOk;
        /** 3. ルート証明書までつながっている */
        boolean chainOk;
        /** つながったルート証明書の名前 */
        String root = "";
        String digestAlgorithm = "";
        /** 確かめられなかった理由 (無ければ null) */
        String error;

        /** 1〜3 がすべて正しい */
        boolean verified() {
            return signed && digestOk && signatureOk && chainOk;
        }

        String summary() {
            if (!signed) return "署名がありません" + (error == null ? "" : " (" + error + ")");
            StringBuilder sb = new StringBuilder("署名者: " + signer + (signerOrg.isEmpty() ? "" : " (" + signerOrg + ")"));
            sb.append("\n  中身と署名の一致: ").append(digestOk ? "正しい" : "合わない");
            sb.append(" / 署名の数値: ").append(signatureOk ? "正しい" : "正しくない");
            sb.append(" / 証明書の連鎖: ").append(chainOk ? "正しい (" + root + " まで)" : "つながらない");
            if (error != null) sb.append("\n  理由: ").append(error);
            return sb.toString();
        }
    }

    private Authenticode() {
    }

    /** f の署名を確かめる。roots は信頼するルート証明書 */
    static Result verify(File f, List<X509Certificate> roots) {
        Result r = new Result();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len < 0x200) throw new IOException("小さすぎます");
            raf.seek(0x3c);
            long pe = Integer.reverseBytes(raf.readInt()) & 0xffffffffL;
            raf.seek(pe);
            if (Integer.reverseBytes(raf.readInt()) != 0x4550) throw new IOException("PE ではありません");
            long opt = pe + 24;
            raf.seek(opt);
            int magic = Short.reverseBytes(raf.readShort()) & 0xffff;
            long checksumAt = opt + 64;
            long secDirAt = opt + (magic == 0x20b ? 112 : 96) + 4 * 8; // データの場所の表の 5 番目 (4) が署名
            raf.seek(secDirAt);
            long certOff = Integer.reverseBytes(raf.readInt()) & 0xffffffffL;
            long certLen = Integer.reverseBytes(raf.readInt()) & 0xffffffffL;
            if (certOff == 0 || certLen < 8 || certOff + certLen > len) {
                r.error = null;
                return r; // 署名なし
            }
            r.signed = true;
            // WIN_CERTIFICATE: 長さ (4)・版 (2)・種類 (2) のあとに PKCS#7 (DER)
            raf.seek(certOff);
            long wlen = Integer.reverseBytes(raf.readInt()) & 0xffffffffL;
            raf.readShort();
            int type = Short.reverseBytes(raf.readShort()) & 0xffff;
            if (type != 2 || wlen < 8 || wlen > certLen) throw new IOException("署名の形が想定と違います");
            byte[] pkcs7 = new byte[(int) (wlen - 8)];
            raf.readFully(pkcs7);

            Der ci = Der.parse(pkcs7, 0);
            List<Der> ciKids = ci.children(pkcs7);
            if (!oid(pkcs7, ciKids.get(0)).equals("1.2.840.113549.1.7.2")) throw new IOException("SignedData ではありません");
            Der sd = ciKids.get(1).children(pkcs7).get(0);
            List<Der> sdKids = sd.children(pkcs7);
            // [version, digestAlgorithms, encapContentInfo, [0] certificates, ([1] crls), signerInfos]
            Der encap = sdKids.get(2);
            List<Der> encKids = encap.children(pkcs7);
            if (!oid(pkcs7, encKids.get(0)).equals("1.3.6.1.4.1.311.2.1.4")) {
                throw new IOException("Authenticode の署名ではありません");
            }
            Der spc = encKids.get(1).children(pkcs7).get(0); // SpcIndirectDataContent
            List<Der> spcKids = spc.children(pkcs7);
            Der digestInfo = spcKids.get(1);
            List<Der> diKids = digestInfo.children(pkcs7);
            String algOid = oid(pkcs7, diKids.get(0).children(pkcs7).get(0));
            String alg = digestName(algOid);
            r.digestAlgorithm = alg;
            byte[] expected = diKids.get(1).value(pkcs7);

            // 1. ファイルの中身のハッシュ値 (チェックサム 4 バイト・署名の場所の表 8 バイト・署名の部分を除く)
            MessageDigest md = MessageDigest.getInstance(alg);
            hashRange(raf, md, 0, checksumAt);
            hashRange(raf, md, checksumAt + 4, secDirAt);
            hashRange(raf, md, secDirAt + 8, certOff);
            hashRange(raf, md, certOff + certLen, len);
            r.digestOk = Arrays.equals(md.digest(), expected);

            // 証明書の一覧
            List<X509Certificate> certs = new ArrayList<>();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Der signerInfos = null;
            for (int i = 3; i < sdKids.size(); i++) {
                Der d = sdKids.get(i);
                if (d.tag == 0xa0) {
                    for (Der c : d.children(pkcs7)) {
                        certs.add((X509Certificate) cf.generateCertificate(
                                new ByteArrayInputStream(pkcs7, c.start, c.end - c.start)));
                    }
                } else if (d.tag == 0x31) {
                    signerInfos = d;
                }
            }
            if (signerInfos == null) throw new IOException("署名者の情報がありません");
            Der si = signerInfos.children(pkcs7).get(0);
            List<Der> siKids = si.children(pkcs7);
            // [version, issuerAndSerialNumber, digestAlgorithm, [0] authenticatedAttributes, digestEncryptionAlgorithm, encryptedDigest, ...]
            List<Der> ias = siKids.get(1).children(pkcs7);
            byte[] serial = ias.get(1).value(pkcs7);
            X509Certificate signerCert = null;
            for (X509Certificate c : certs) {
                if (Arrays.equals(c.getSerialNumber().toByteArray(), serial)) signerCert = c;
            }
            if (signerCert == null) throw new IOException("署名者の証明書が見つかりません");
            r.signer = field(signerCert.getSubjectX500Principal().getName(), "CN");
            r.signerOrg = field(signerCert.getSubjectX500Principal().getName(), "O");

            // 2. 署名した内容 (SpcIndirectDataContent の中身) のハッシュ値と、署名そのもの
            Der attrs = siKids.get(3);
            if (attrs.tag != 0xa0) throw new IOException("署名の属性がありません");
            byte[] contentHash = MessageDigest.getInstance(digestName(
                    oid(pkcs7, siKids.get(2).children(pkcs7).get(0)))).digest(
                    Arrays.copyOfRange(pkcs7, spc.valueStart, spc.end));
            boolean mdOk = false;
            for (Der a : attrs.children(pkcs7)) {
                List<Der> ak = a.children(pkcs7);
                if (oid(pkcs7, ak.get(0)).equals("1.2.840.113549.1.9.4")) {
                    byte[] v = ak.get(1).children(pkcs7).get(0).value(pkcs7);
                    mdOk = Arrays.equals(v, contentHash);
                }
            }
            byte[] signedBytes = Arrays.copyOfRange(pkcs7, attrs.start, attrs.end);
            signedBytes[0] = 0x31; // 署名は [0] ではなく SET として計算される
            String sigAlg = digestName(oid(pkcs7, siKids.get(2).children(pkcs7).get(0))).replace("-", "") + "withRSA";
            Signature sig = Signature.getInstance(sigAlg);
            sig.initVerify(signerCert.getPublicKey());
            sig.update(signedBytes);
            r.signatureOk = mdOk && sig.verify(siKids.get(5).value(pkcs7));

            // 3. 証明書の連鎖 (期限は見ない)
            X509Certificate cur = signerCert;
            for (int depth = 0; depth < 6 && !r.chainOk; depth++) {
                X509Certificate root = issuerIn(cur, roots);
                if (root != null) {
                    cur.verify(root.getPublicKey());
                    r.chainOk = true;
                    r.root = field(root.getSubjectX500Principal().getName(), "CN");
                    break;
                }
                X509Certificate up = issuerIn(cur, certs);
                if (up == null || up == cur) break;
                cur.verify(up.getPublicKey());
                cur = up;
            }
            if (!r.chainOk && r.error == null) r.error = "信頼するルート証明書 (Microsoft のルート) までつながりません";
        } catch (Exception e) {
            if (r.error == null) r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    private static X509Certificate issuerIn(X509Certificate c, List<X509Certificate> list) {
        for (X509Certificate x : list) {
            if (x.getSubjectX500Principal().equals(c.getIssuerX500Principal())) return x;
        }
        return null;
    }

    private static void hashRange(RandomAccessFile raf, MessageDigest md, long from, long to) throws IOException {
        byte[] buf = new byte[1 << 16];
        raf.seek(from);
        long left = to - from;
        while (left > 0) {
            int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
            if (n <= 0) throw new IOException("読み込みが途中で終わりました");
            md.update(buf, 0, n);
            left -= n;
        }
    }

    private static String digestName(String oid) throws IOException {
        switch (oid) {
            case "2.16.840.1.101.3.4.2.1":
                return "SHA-256";
            case "2.16.840.1.101.3.4.2.2":
                return "SHA-384";
            case "2.16.840.1.101.3.4.2.3":
                return "SHA-512";
            case "1.3.14.3.2.26":
                return "SHA-1";
            default:
                throw new IOException("知らないハッシュの方式です: " + oid);
        }
    }

    /** 「CN=…,O=…」のような名前から 1 つ取り出す */
    static String field(String dn, String key) {
        for (String part : dn.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1).replace("\"", "");
        }
        return "";
    }

    private static String oid(byte[] b, Der d) throws IOException {
        if (d.tag != 0x06) throw new IOException("形が想定と違います (OID)");
        StringBuilder sb = new StringBuilder();
        int i = d.valueStart;
        int first = b[i++] & 0xff;
        sb.append(first / 40).append('.').append(first % 40);
        long v = 0;
        for (; i < d.end; i++) {
            v = (v << 7) | (b[i] & 0x7f);
            if ((b[i] & 0x80) == 0) {
                sb.append('.').append(v);
                v = 0;
            }
        }
        return sb.toString();
    }

    /** DER (ASN.1) の 1 要素: 種類 (tag)・全体の範囲・中身の始まり */
    static final class Der {
        int tag;
        int start, valueStart, end;

        static Der parse(byte[] b, int at) throws IOException {
            if (at + 2 > b.length) throw new IOException("形が想定と違います (途中で終わっています)");
            Der d = new Der();
            d.start = at;
            d.tag = b[at] & 0xff;
            int p = at + 1;
            int l = b[p++] & 0xff;
            long len;
            if (l < 0x80) {
                len = l;
            } else {
                int n = l & 0x7f;
                if (n == 0 || n > 4) throw new IOException("形が想定と違います (長さ)");
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (b[p++] & 0xff);
            }
            d.valueStart = p;
            if (p + len > b.length) throw new IOException("形が想定と違います (長さが大きすぎます)");
            d.end = (int) (p + len);
            return d;
        }

        List<Der> children(byte[] b) throws IOException {
            List<Der> out = new ArrayList<>();
            int p = valueStart;
            while (p < end) {
                Der c = parse(b, p);
                out.add(c);
                p = c.end;
            }
            return out;
        }

        byte[] value(byte[] b) {
            return Arrays.copyOfRange(b, valueStart, end);
        }
    }
}
