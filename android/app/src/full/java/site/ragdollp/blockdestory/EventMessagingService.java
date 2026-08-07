package site.ragdollp.blockdestory;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * イベント告知の受信 (Firebase Cloud Messaging)。
 * Firebase コンソール → Messaging から「events」トピック宛に送ると、
 * アプリを閉じていてもイベント通知が届く。
 */
public class EventMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = null, body = null;
        if (message.getNotification() != null) {
            title = message.getNotification().getTitle();
            body = message.getNotification().getBody();
        }
        if ((title == null || title.isEmpty()) && message.getData() != null) {
            String dt = message.getData().get("title");
            String db = message.getData().get("body");
            if (dt != null) title = dt;
            if (db != null) body = db;
        }
        DailyNotify.postEvent(getApplicationContext(), title, body);
    }

    @Override
    public void onNewToken(String token) {
        // ブロードキャストは「events」トピック購読で行うため、個別トークンは未使用。
        try { FirebaseMessaging.getInstance().subscribeToTopic("events"); } catch (Throwable ignore) { }
    }
}
