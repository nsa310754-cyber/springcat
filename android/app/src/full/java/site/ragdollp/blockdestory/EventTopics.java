package site.ragdollp.blockdestory;

import android.content.Context;

/** full 版: イベント告知 (FCM) の "events" トピックを購読する。 */
final class EventTopics {
    private EventTopics() { }

    static void subscribe(Context ctx) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("events");
        } catch (Throwable ignore) { }
    }
}
