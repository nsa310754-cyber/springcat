import UIKit
import WebKit
import CommonCrypto

/// Block Destroy を iOS アプリとして動かす WKWebView ラッパー。
///
/// ゲーム本体 (game.html) は Android と同じものを AES-256-CBC で暗号化した
/// `game.enc` として同梱し、起動時にメモリ上で復号して WKWebView に読み込む。
/// 平文 HTML は .ipa に含まれないため、unzip での直読みを防げる
/// (クライアント側の難読化であり「解読不能」ではない。鍵はアプリ内にある)。
///
/// ⚠️ パスフレーズは Android 側 (build.gradle の gameEncPass /
///    MainActivity の GAME_ENC_PARTS) と必ず一致させること。
final class GameViewController: UIViewController, WKNavigationDelegate {

    private var webView: WKWebView!

    // 🔒 復号パスフレーズ (Android と一致)。分割してソースgrepを少し面倒にしている。
    private static let gameEncPass = "bd350-" + "ragdollp-" + "appassets-" + "obf-k3y"

    // ゲーム背景 (水色→ピンク) の下端色。余白が黒くならないように。
    private static let bgColor = UIColor(red: 0xF2/255.0, green: 0xA0/255.0, blue: 0xF1/255.0, alpha: 1)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = GameViewController.bgColor
        setupWebView()
        loadGame()
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .darkContent }

    // MARK: - WebView セットアップ

    private func setupWebView() {
        let config = WKWebViewConfiguration()

        // localStorage / IndexedDB を端末に永続化 (セーブデータはすべて localStorage)。
        config.websiteDataStore = .default()

        // 動画・録画プレビュー等をユーザー操作なしで再生できるように。
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        let prefs = WKPreferences()
        prefs.javaScriptCanOpenWindowsAutomatically = true
        config.preferences = prefs

        // ⭐ ゲームの WebView 判定フックを通す + デスクトップレイアウトに固定。
        //    Android は viewport を width=1024 に固定して常にデスクトップUIを
        //    画面幅にフィット表示している。iOS でも同じ挙動に合わせる。
        let fixViewport = """
        (function(){var m=document.querySelector('meta[name=viewport]');\
        if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');\
        (document.head||document.documentElement).appendChild(m);}\
        m.setAttribute('content','width=1024, user-scalable=no, viewport-fit=cover');})();
        """
        let ucc = WKUserContentController()
        ucc.addUserScript(WKUserScript(source: fixViewport,
                                       injectionTime: .atDocumentEnd,
                                       forMainFrameOnly: true))
        config.userContentController = ucc

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = GameViewController.bgColor
        webView.scrollView.backgroundColor = GameViewController.bgColor
        webView.allowsBackForwardNavigationGestures = false

        // ★ ゲームの「アプリ版」判定フック (Android の " BlockdestoryApp/1" 相当)。
        //    UA に BlockdestoryApp を含めると、アプリ内向けの表示に切り替わる。
        webView.customUserAgent =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 BlockdestoryApp/1"

        view.addSubview(webView)
    }

    // MARK: - ゲーム読み込み (復号 → ファイル出力 → loadFileURL)

    private func loadGame() {
        guard let html = decryptedGameHTML() else {
            showError("ゲームデータの読み込みに失敗しました (game.enc)")
            return
        }
        do {
            // Application Support 配下に平文を書き出し、file:// で読み込む。
            //   file:// オリジンは WKWebView で localStorage が安定して永続化される。
            let dir = try FileManager.default.url(for: .applicationSupportDirectory,
                                                  in: .userDomainMask,
                                                  appropriateFor: nil,
                                                  create: true)
                .appendingPathComponent("BlockDestroy", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let gameURL = dir.appendingPathComponent("game.html")
            try html.write(to: gameURL)
            webView.loadFileURL(gameURL, allowingReadAccessTo: dir)
        } catch {
            showError("一時ファイルの作成に失敗しました: \(error.localizedDescription)")
        }
    }

    /// 同梱 game.enc を復号して HTML の Data を返す。IV(先頭16byte) + 暗号文。
    private func decryptedGameHTML() -> Data? {
        guard let encURL = Bundle.main.url(forResource: "game", withExtension: "enc"),
              let enc = try? Data(contentsOf: encURL),
              enc.count > 16 else { return nil }

        let key = sha256(Data(GameViewController.gameEncPass.utf8))
        let iv = enc.subdata(in: 0..<16)
        let cipher = enc.subdata(in: 16..<enc.count)
        return aes256CBCDecrypt(data: cipher, key: key, iv: iv)
    }

    // MARK: - 暗号ユーティリティ (CommonCrypto)

    private func sha256(_ data: Data) -> Data {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &digest) }
        return Data(digest)
    }

    private func aes256CBCDecrypt(data: Data, key: Data, iv: Data) -> Data? {
        let bufferSize = data.count + kCCBlockSizeAES128
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        var numBytesDecrypted = 0

        let status = key.withUnsafeBytes { keyPtr in
            iv.withUnsafeBytes { ivPtr in
                data.withUnsafeBytes { dataPtr in
                    CCCrypt(CCOperation(kCCDecrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyPtr.baseAddress, key.count,
                            ivPtr.baseAddress,
                            dataPtr.baseAddress, data.count,
                            &buffer, bufferSize,
                            &numBytesDecrypted)
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return Data(bytes: buffer, count: numBytesDecrypted)
    }

    // MARK: - 補助

    private func showError(_ message: String) {
        let label = UILabel(frame: view.bounds)
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .white
        label.text = "😢 \(message)"
        view.addSubview(label)
    }

    // 画面を消灯させない (Android の FLAG_KEEP_SCREEN_ON 相当)
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        UIApplication.shared.isIdleTimerDisabled = true
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        UIApplication.shared.isIdleTimerDisabled = false
    }
}
