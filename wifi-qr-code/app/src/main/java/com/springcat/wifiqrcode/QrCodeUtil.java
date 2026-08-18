package com.springcat.wifiqrcode;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** WIFI: 形式の文字列組み立てと、QRコード(ZXing)のビットマップ化のみを行う。ネットワーク通信は行わない。 */
final class QrCodeUtil {

    private QrCodeUtil() {
    }

    /**
     * Wi-Fi接続用QRコードの標準フォーマット文字列を組み立てる。
     * 例: WIFI:T:WPA;S:MyNetwork;P:MyPassword;H:false;;
     */
    static String buildWifiQrText(String ssid, String password, String securityType, boolean hidden) {
        StringBuilder sb = new StringBuilder("WIFI:");
        sb.append("T:").append(securityType).append(';');
        sb.append("S:").append(escape(ssid)).append(';');
        if (!"nopass".equals(securityType)) {
            sb.append("P:").append(escape(password)).append(';');
        }
        if (hidden) {
            sb.append("H:true;");
        }
        sb.append(';');
        return sb.toString();
    }

    /** WIFI: フォーマットで特別な意味を持つ文字 (\ ; , : ") をエスケープする。 */
    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    static Bitmap encodeToBitmap(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }
}
