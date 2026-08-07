package site.ragdollp.blockdestory;

import android.content.Context;

/** lite 版: Firebase Cloud Messaging を同梱しないため購読処理は不要 (no-op)。 */
final class EventTopics {
    private EventTopics() { }

    static void subscribe(Context ctx) { }
}
