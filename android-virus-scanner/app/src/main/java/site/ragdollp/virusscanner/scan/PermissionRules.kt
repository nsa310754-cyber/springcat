package site.ragdollp.virusscanner.scan

/**
 * マルウェアに悪用されやすい「危険な権限」のカタログ。
 *
 * これらは単体では正当なアプリ(カメラアプリのCAMERA権限など)でもよく使われるため、
 * 1つだけ要求している場合は正常なアプリの可能性が高いとみなし検出対象から外す。
 * 複数を同時に要求している場合のみ、組み合わせとして不審と判定する。
 */
object PermissionRules {

    val SUSPICIOUS_PERMISSIONS: Map<String, String> = linkedMapOf(
        "android.permission.SEND_SMS" to "SMSの送信",
        "android.permission.RECEIVE_SMS" to "SMSの受信・傍受",
        "android.permission.READ_SMS" to "SMSの読み取り",
        "android.permission.RECEIVE_MMS" to "MMSの受信",
        "android.permission.CALL_PHONE" to "電話の発信",
        "android.permission.PROCESS_OUTGOING_CALLS" to "発信通話の監視",
        "android.permission.READ_CALL_LOG" to "通話履歴の読み取り",
        "android.permission.WRITE_CALL_LOG" to "通話履歴の書き換え",
        "android.permission.READ_CONTACTS" to "連絡先の読み取り",
        "android.permission.WRITE_CONTACTS" to "連絡先の書き換え",
        "android.permission.RECORD_AUDIO" to "マイクの録音",
        "android.permission.CAMERA" to "カメラの使用",
        "android.permission.ACCESS_FINE_LOCATION" to "詳細な位置情報の取得",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "バックグラウンドでの位置情報取得",
        "android.permission.SYSTEM_ALERT_WINDOW" to "他アプリの上への重ね表示(オーバーレイ)",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "他アプリの追加インストール",
        "android.permission.BIND_DEVICE_ADMIN" to "端末管理者権限の要求",
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to "ユーザー補助サービスの悪用余地",
        "android.permission.QUERY_ALL_PACKAGES" to "端末内の全アプリ一覧の取得",
        "android.permission.READ_PHONE_STATE" to "端末識別情報の取得",
        "android.permission.WRITE_SETTINGS" to "システム設定の変更",
        "android.permission.PACKAGE_USAGE_STATS" to "他アプリの使用状況の取得",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to "通知内容の読み取り",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "全ファイルへのアクセス",
        "android.permission.RECEIVE_BOOT_COMPLETED" to "端末起動時の自動起動(常駐)",
        "android.permission.GET_ACCOUNTS" to "登録済みアカウント一覧の取得",
        "android.permission.ANSWER_PHONE_CALLS" to "電話の自動応答",
        "android.permission.BODY_SENSORS" to "身体センサー情報の取得",
        "android.permission.READ_PHONE_NUMBERS" to "電話番号の取得",
    )

    // これ未満(=1つだけ)なら正常アプリの可能性が高いため、検出対象に含めないしきい値。
    const val SUSPICION_THRESHOLD = 2
}
