/*
 * Short, human-readable descriptions for common Android permissions.
 * Used for display only - editing works with any permission string,
 * known or not.
 */
(function (global) {
  'use strict';

  var P = 'android.permission.';
  var CATALOG = {};
  function add(name, label, danger) { CATALOG[name] = { label: label, danger: !!danger }; }

  add(P + 'INTERNET', 'インターネット通信', false);
  add(P + 'ACCESS_NETWORK_STATE', 'ネットワーク接続状態の取得', false);
  add(P + 'ACCESS_WIFI_STATE', 'Wi-Fi接続状態の取得', false);
  add(P + 'CHANGE_WIFI_STATE', 'Wi-Fi設定の変更', false);
  add(P + 'CAMERA', 'カメラの使用', true);
  add(P + 'RECORD_AUDIO', 'マイクでの録音', true);
  add(P + 'READ_CONTACTS', '連絡先の読み取り', true);
  add(P + 'WRITE_CONTACTS', '連絡先の変更', true);
  add(P + 'READ_EXTERNAL_STORAGE', 'ストレージ内ファイルの読み取り', true);
  add(P + 'WRITE_EXTERNAL_STORAGE', 'ストレージ内ファイルの書き込み', true);
  add(P + 'READ_MEDIA_IMAGES', '画像ファイルの読み取り', true);
  add(P + 'READ_MEDIA_VIDEO', '動画ファイルの読み取り', true);
  add(P + 'READ_MEDIA_AUDIO', '音声ファイルの読み取り', true);
  add(P + 'ACCESS_FINE_LOCATION', '正確な位置情報の取得', true);
  add(P + 'ACCESS_COARSE_LOCATION', 'おおよその位置情報の取得', true);
  add(P + 'ACCESS_BACKGROUND_LOCATION', 'バックグラウンドでの位置情報取得', true);
  add(P + 'POST_NOTIFICATIONS', '通知の送信', false);
  add(P + 'BLUETOOTH', 'Bluetoothの使用（旧API）', false);
  add(P + 'BLUETOOTH_ADMIN', 'Bluetoothの管理（旧API）', false);
  add(P + 'BLUETOOTH_CONNECT', 'Bluetoothデバイスへの接続', false);
  add(P + 'BLUETOOTH_SCAN', 'Bluetoothデバイスの検索', true);
  add(P + 'CALL_PHONE', '電話の発信', true);
  add(P + 'READ_PHONE_STATE', '端末の電話状態の取得', true);
  add(P + 'READ_CALL_LOG', '通話履歴の読み取り', true);
  add(P + 'SEND_SMS', 'SMSの送信', true);
  add(P + 'READ_SMS', 'SMSの読み取り', true);
  add(P + 'RECEIVE_BOOT_COMPLETED', '端末起動時の自動起動', false);
  add(P + 'VIBRATE', '端末を振動させる', false);
  add(P + 'WAKE_LOCK', '画面がオフでも処理を継続', false);
  add(P + 'FOREGROUND_SERVICE', 'フォアグラウンドサービスの実行', false);
  add(P + 'BODY_SENSORS', '心拍数などの体のセンサー情報の取得', true);
  add(P + 'ACTIVITY_RECOGNITION', '身体活動（歩行・走行など）の認識', true);
  add(P + 'USE_BIOMETRIC', '指紋・顔認証の利用', false);
  add(P + 'USE_FINGERPRINT', '指紋認証の利用（旧API）', false);
  add(P + 'NFC', 'NFC（近距離無線通信）の使用', false);
  add(P + 'QUERY_ALL_PACKAGES', '端末にインストールされた全アプリの一覧取得', true);
  add(P + 'SYSTEM_ALERT_WINDOW', '他のアプリの上に重ねて表示', true);
  add(P + 'REQUEST_INSTALL_PACKAGES', '他のアプリのインストールを要求', true);
  add(P + 'SCHEDULE_EXACT_ALARM', '正確な時刻でのアラーム設定', false);

  var COMMON_ADDABLE = [
    P + 'INTERNET', P + 'ACCESS_NETWORK_STATE', P + 'ACCESS_WIFI_STATE',
    P + 'CAMERA', P + 'RECORD_AUDIO', P + 'READ_CONTACTS', P + 'WRITE_CONTACTS',
    P + 'READ_MEDIA_IMAGES', P + 'READ_MEDIA_VIDEO', P + 'READ_MEDIA_AUDIO',
    P + 'ACCESS_FINE_LOCATION', P + 'ACCESS_COARSE_LOCATION',
    P + 'POST_NOTIFICATIONS', P + 'BLUETOOTH_CONNECT', P + 'BLUETOOTH_SCAN',
    P + 'CALL_PHONE', P + 'READ_PHONE_STATE', P + 'SEND_SMS', P + 'READ_SMS',
    P + 'VIBRATE', P + 'WAKE_LOCK', P + 'FOREGROUND_SERVICE', P + 'BODY_SENSORS',
    P + 'ACTIVITY_RECOGNITION', P + 'NFC', P + 'SYSTEM_ALERT_WINDOW'
  ];

  var PermissionsCatalog = { CATALOG: CATALOG, COMMON_ADDABLE: COMMON_ADDABLE };
  if (typeof module !== 'undefined' && module.exports) module.exports = PermissionsCatalog;
  global.PermissionsCatalog = PermissionsCatalog;
})(typeof window !== 'undefined' ? window : global);
