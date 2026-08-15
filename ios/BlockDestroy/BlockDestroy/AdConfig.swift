import Foundation

/// 📢 iOS 用 AdMob 設定。
///
/// v1 の WKWebView ラッパーでは広告 SDK はまだ組み込んでいない (ゲームは広告なしで
/// 完全動作する)。ここに iOS 用の ID を控えておき、Google Mobile Ads SDK を追加した
/// ときにそのまま使えるようにしている。
///
/// 有効化手順は ios/README.md の「📢 広告(AdMob)を有効にするには」を参照。
///
/// ※ Android 用 (`~9713546902` / `/8995507392`) とは別 ID。iOS はこちらを使うこと。
enum AdConfig {
    /// アプリ ID (Info.plist の GADApplicationIdentifier にも同じ値を設定済み)
    static let applicationID = "ca-app-pub-8357981710510236~6093403070"

    /// ネイティブアドバンス広告ユニット ID
    static let nativeAdUnitID = "ca-app-pub-8357981710510236/1056698817"
}
