package site.ragdollp.copytool;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

final class AccessibilityUtil {

    private AccessibilityUtil() {}

    /** 指定のアクセシビリティサービスが現在ONになっているか。 */
    static boolean isEnabled(Context context, Class<?> serviceClass) {
        String expected = context.getPackageName() + "/" + serviceClass.getCanonicalName();
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }
}
