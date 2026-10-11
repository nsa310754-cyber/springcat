package site.ragdollp.bigclip;

import java.util.Locale;

final class Fmt {
    private Fmt() {}

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.2f MB", n / (1024.0 * 1024.0));
    }
}
