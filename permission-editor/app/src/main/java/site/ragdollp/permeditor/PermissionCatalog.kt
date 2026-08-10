package site.ragdollp.permeditor

/** Short, human-readable descriptions for common Android permissions. Display only. */
object PermissionCatalog {
    data class Info(val label: String, val danger: Boolean)

    private const val P = "android.permission."

    val CATALOG: Map<String, Info> = mapOf(
        "${P}INTERNET" to Info("インターネット通信", false),
        "${P}ACCESS_NETWORK_STATE" to Info("ネットワーク接続状態の取得", false),
        "${P}ACCESS_WIFI_STATE" to Info("Wi-Fi接続状態の取得", false),
        "${P}CHANGE_WIFI_STATE" to Info("Wi-Fi設定の変更", false),
        "${P}CAMERA" to Info("カメラの使用", true),
        "${P}RECORD_AUDIO" to Info("マイクでの録音", true),
        "${P}READ_CONTACTS" to Info("連絡先の読み取り", true),
        "${P}WRITE_CONTACTS" to Info("連絡先の変更", true),
        "${P}READ_EXTERNAL_STORAGE" to Info("ストレージ内ファイルの読み取り", true),
        "${P}WRITE_EXTERNAL_STORAGE" to Info("ストレージ内ファイルの書き込み", true),
        "${P}READ_MEDIA_IMAGES" to Info("画像ファイルの読み取り", true),
        "${P}READ_MEDIA_VIDEO" to Info("動画ファイルの読み取り", true),
        "${P}READ_MEDIA_AUDIO" to Info("音声ファイルの読み取り", true),
        "${P}ACCESS_FINE_LOCATION" to Info("正確な位置情報の取得", true),
        "${P}ACCESS_COARSE_LOCATION" to Info("おおよその位置情報の取得", true),
        "${P}ACCESS_BACKGROUND_LOCATION" to Info("バックグラウンドでの位置情報取得", true),
        "${P}POST_NOTIFICATIONS" to Info("通知の送信", false),
        "${P}BLUETOOTH" to Info("Bluetoothの使用（旧API）", false),
        "${P}BLUETOOTH_ADMIN" to Info("Bluetoothの管理（旧API）", false),
        "${P}BLUETOOTH_CONNECT" to Info("Bluetoothデバイスへの接続", false),
        "${P}BLUETOOTH_SCAN" to Info("Bluetoothデバイスの検索", true),
        "${P}CALL_PHONE" to Info("電話の発信", true),
        "${P}READ_PHONE_STATE" to Info("端末の電話状態の取得", true),
        "${P}READ_CALL_LOG" to Info("通話履歴の読み取り", true),
        "${P}SEND_SMS" to Info("SMSの送信", true),
        "${P}READ_SMS" to Info("SMSの読み取り", true),
        "${P}RECEIVE_BOOT_COMPLETED" to Info("端末起動時の自動起動", false),
        "${P}VIBRATE" to Info("端末を振動させる", false),
        "${P}WAKE_LOCK" to Info("画面がオフでも処理を継続", false),
        "${P}FOREGROUND_SERVICE" to Info("フォアグラウンドサービスの実行", false),
        "${P}BODY_SENSORS" to Info("心拍数などの体のセンサー情報の取得", true),
        "${P}ACTIVITY_RECOGNITION" to Info("身体活動（歩行・走行など）の認識", true),
        "${P}USE_BIOMETRIC" to Info("指紋・顔認証の利用", false),
        "${P}NFC" to Info("NFC（近距離無線通信）の使用", false),
        "${P}QUERY_ALL_PACKAGES" to Info("端末にインストールされた全アプリの一覧取得", true),
        "${P}SYSTEM_ALERT_WINDOW" to Info("他のアプリの上に重ねて表示", true),
        "${P}REQUEST_INSTALL_PACKAGES" to Info("他のアプリのインストールを要求", true),
        "${P}SCHEDULE_EXACT_ALARM" to Info("正確な時刻でのアラーム設定", false)
    )

    val COMMON_ADDABLE: List<String> = listOf(
        "${P}INTERNET", "${P}ACCESS_NETWORK_STATE", "${P}ACCESS_WIFI_STATE",
        "${P}CAMERA", "${P}RECORD_AUDIO", "${P}READ_CONTACTS", "${P}WRITE_CONTACTS",
        "${P}READ_MEDIA_IMAGES", "${P}READ_MEDIA_VIDEO", "${P}READ_MEDIA_AUDIO",
        "${P}ACCESS_FINE_LOCATION", "${P}ACCESS_COARSE_LOCATION",
        "${P}POST_NOTIFICATIONS", "${P}BLUETOOTH_CONNECT", "${P}BLUETOOTH_SCAN",
        "${P}CALL_PHONE", "${P}READ_PHONE_STATE", "${P}SEND_SMS", "${P}READ_SMS",
        "${P}VIBRATE", "${P}WAKE_LOCK", "${P}FOREGROUND_SERVICE", "${P}BODY_SENSORS",
        "${P}ACTIVITY_RECOGNITION", "${P}NFC", "${P}SYSTEM_ALERT_WINDOW"
    )
}
