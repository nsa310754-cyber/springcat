import UIKit
import WebKit

/// Block Destroy を iOS アプリとして動かすための WKWebView ラッパー。
/// Android 版 (android/.../MainActivity.java) と同じ役割を持つ:
///  - ゲーム本体 (Resources/game.html) はオフラインで完結して動作する
///  - UserAgent に "BlockdestoryApp/1" を付与 (game.html の簡易ブラウザ判定を通すため)
///  - viewport 強制 (常にデスクトップレイアウトで表示し、幅に合わせて縮小フィットさせる)
///  - blob/data の <a download> を横取りして端末 (写真アプリ / Files) へ保存
///  - デイリーボーナス通知・端末固有ID・画面録画 (ReplayKit) をネイティブブリッジで提供
final class ViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    private var webView: WKWebView!
    private var toastLabel: UILabel?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0xF2 / 255.0, green: 0xA0 / 255.0, blue: 0xF1 / 255.0, alpha: 1)

        UIApplication.shared.isIdleTimerDisabled = true

        let controller = WKUserContentController()
        controller.add(self, name: "native")
        controller.addUserScript(WKUserScript(
            source: Self.bridgeScript(deviceId: Self.deviceId()),
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))

        let config = WKWebViewConfiguration()
        config.userContentController = controller
        config.applicationNameForUserAgent = "BlockdestoryApp/1"
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.websiteDataStore = .default()

        webView = WKWebView(frame: .zero, configuration: config)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.backgroundColor = view.backgroundColor
        webView.scrollView.backgroundColor = view.backgroundColor
        webView.isOpaque = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.scrollView.bounces = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        if let url = Bundle.main.url(forResource: "game", withExtension: "html") {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
    }

    override var prefersStatusBarHidden: Bool { false }
    override var preferredStatusBarStyle: UIStatusBarStyle { .darkContent }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if ScreenRecorder.shared.isRecording { stopRecording() }
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.evaluateJavaScript("window.__fixViewport && window.__fixViewport();")
        DailyNotify.shared.checkPermission { [weak self] granted in
            self?.pushNotifyGranted(granted)
        }
    }

    // MARK: - WKUIDelegate (ゲームからの window.open / alert をアプリ内で吸収する)

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url {
            UIApplication.shared.open(url)
        }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    // MARK: - WKScriptMessageHandler (JS ↔ ネイティブ ブリッジ)

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let action = body["action"] as? String else { return }

        switch action {
        case "save":
            guard let name = body["name"] as? String,
                  let mime = body["mime"] as? String,
                  let base64 = body["base64"] as? String else { return }
            MediaSaver.save(name: name, mime: mime, base64: base64) { [weak self] ok in
                self?.toast(ok ? "保存しました: \(name)" : "保存に失敗しました")
            }

        case "requestPermission":
            DailyNotify.shared.requestPermission { [weak self] granted in
                self?.pushNotifyGranted(granted)
                self?.webView.evaluateJavaScript(
                    "try{ if(typeof syncNotifyUI==='function') syncNotifyUI();" +
                    " if(typeof scheduleDailyNotify==='function') scheduleDailyNotify(); }catch(e){}")
            }

        case "scheduleDaily":
            if let hour = body["hour"] as? Int, let minute = body["min"] as? Int {
                DailyNotify.shared.scheduleDaily(hour: hour, minute: minute)
            }

        case "cancel":
            DailyNotify.shared.cancel()

        case "notifyNow":
            let title = body["title"] as? String ?? ""
            let text = body["body"] as? String ?? ""
            DailyNotify.shared.notifyNow(title: title, body: text)

        case "startRecording":
            startRecording()

        case "stopRecording":
            stopRecording()

        default:
            break
        }
    }

    private func pushNotifyGranted(_ granted: Bool) {
        webView.evaluateJavaScript("window.__iosNotifyGranted = \(granted); try{ if(typeof syncNotifyUI==='function') syncNotifyUI(); }catch(e){}")
    }

    // MARK: - 画面録画 (ReplayKit)

    private func startRecording() {
        ScreenRecorder.shared.start { [weak self] ok in
            if ok {
                self?.pushRecordingUI(true)
                self?.toast("録画を開始しました")
            } else {
                self?.toast("録画を開始できませんでした")
            }
        }
    }

    private func stopRecording() {
        ScreenRecorder.shared.stop { [weak self] url in
            self?.pushRecordingUI(false)
            guard let url else {
                self?.toast("録画の保存に失敗しました")
                return
            }
            MediaSaver.saveVideoFile(at: url) { ok in
                try? FileManager.default.removeItem(at: url)
                self?.toast(ok ? "録画を保存しました" : "録画の保存に失敗しました")
            }
        }
    }

    private func pushRecordingUI(_ on: Bool) {
        webView.evaluateJavaScript("window.__iosRecording = \(on); window.__setRecUI && window.__setRecUI(\(on));")
    }

    // MARK: - 端末固有ID

    private static func deviceId() -> String {
        UIDevice.current.identifierForVendor?.uuidString ?? ""
    }

    // MARK: - トースト風の簡易通知

    private func toast(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.toastLabel?.removeFromSuperview()

            let label = UILabel()
            label.text = message
            label.textColor = .white
            label.backgroundColor = UIColor.black.withAlphaComponent(0.75)
            label.font = .systemFont(ofSize: 14)
            label.textAlignment = .center
            label.numberOfLines = 0
            label.layer.cornerRadius = 10
            label.clipsToBounds = true
            label.alpha = 0
            label.translatesAutoresizingMaskIntoConstraints = false
            self.view.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: self.view.centerXAnchor),
                label.bottomAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
                label.leadingAnchor.constraint(greaterThanOrEqualTo: self.view.leadingAnchor, constant: 24),
                label.trailingAnchor.constraint(lessThanOrEqualTo: self.view.trailingAnchor, constant: -24),
            ])
            self.toastLabel = label

            UIView.animate(withDuration: 0.2, animations: { label.alpha = 1 }, completion: { _ in
                UIView.animate(withDuration: 0.3, delay: 1.6, options: [], animations: { label.alpha = 0 }, completion: { _ in
                    label.removeFromSuperview()
                })
            })
        }
    }

    // MARK: - 注入する JS (ダウンロード横取り + ネイティブブリッジ定義)

    private static func bridgeScript(deviceId: String) -> String {
        """
        (function(){
          // 常にデスクトップレイアウトで表示し、画面幅に縮小フィットさせる。
          window.__fixViewport = function(){
            var m = document.querySelector('meta[name=viewport]');
            if (!m) {
              m = document.createElement('meta');
              m.setAttribute('name', 'viewport');
              (document.head || document.documentElement).appendChild(m);
            }
            m.setAttribute('content', 'width=1024, user-scalable=no, viewport-fit=cover');
          };
          window.__fixViewport();
          document.addEventListener('DOMContentLoaded', window.__fixViewport);

          // blob:/data: の <a download> を横取りしてネイティブ保存 (写真アプリ / Files)
          if (!window.__dlHook) {
            window.__dlHook = true;
            document.addEventListener('click', function(e){
              var a = e.target;
              while (a && (!a.tagName || a.tagName.toUpperCase() !== 'A')) a = a.parentElement;
              if (!a || !a.hasAttribute('download')) return;
              var href = a.getAttribute('href') || a.href || '';
              if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
              e.preventDefault(); e.stopPropagation();
              var name = a.getAttribute('download') || 'download';
              fetch(href).then(function(r){ return r.blob(); }).then(function(blob){
                var fr = new FileReader();
                fr.onload = function(){
                  var res = String(fr.result);
                  var c = res.indexOf(',');
                  var meta = res.substring(5, c);
                  var b64 = res.substring(c + 1);
                  var mime = (meta.split(';')[0]) || 'application/octet-stream';
                  try { window.webkit.messageHandlers.native.postMessage({action:'save', name:name, mime:mime, base64:b64}); } catch(err){}
                };
                fr.readAsDataURL(blob);
              }).catch(function(){});
            }, true);
          }

          // 端末固有ID (アプリ再インストールでも Apple の identifierForVendor が変わらない限り安定)
          window.AndroidDevice = { getId: function(){ return \(jsString(deviceId)); } };

          // デイリーボーナス通知
          window.__iosNotifyGranted = false;
          window.AndroidNotify = {
            isSupported: function(){ return true; },
            hasPermission: function(){ return window.__iosNotifyGranted === true; },
            requestPermission: function(){
              try { window.webkit.messageHandlers.native.postMessage({action:'requestPermission'}); } catch(e){}
              return window.__iosNotifyGranted === true;
            },
            scheduleDaily: function(hour, min){
              try { window.webkit.messageHandlers.native.postMessage({action:'scheduleDaily', hour:hour, min:min}); } catch(e){}
            },
            cancel: function(){
              try { window.webkit.messageHandlers.native.postMessage({action:'cancel'}); } catch(e){}
            },
            notifyNow: function(title, body){
              try { window.webkit.messageHandlers.native.postMessage({action:'notifyNow', title:title, body:body}); } catch(e){}
            }
          };

          // 画面録画 (ReplayKit) をゲームの録画ボタンへ結線
          window.__iosRecording = false;
          window.AndroidRecorder = {
            isRecording: function(){ return window.__iosRecording === true; },
            start: function(){ try { window.webkit.messageHandlers.native.postMessage({action:'startRecording'}); } catch(e){} },
            stop: function(){ try { window.webkit.messageHandlers.native.postMessage({action:'stopRecording'}); } catch(e){} }
          };
          window.toggleRecording = function(){
            try { if (window.AndroidRecorder.isRecording()) window.AndroidRecorder.stop(); else window.AndroidRecorder.start(); } catch(e){}
          };
          window.__setRecUI = function(on){
            var b = document.getElementById('recordBtn');
            if (b) {
              b.textContent = on ? '⏹ 録画停止' : '🎥 録画開始';
              if (on) b.classList.add('recording'); else b.classList.remove('recording');
            }
            var ind = document.getElementById('recIndicator');
            if (ind) ind.style.display = on ? 'block' : 'none';
          };
        })();
        """
    }

    private static func jsString(_ s: String) -> String {
        let escaped = s
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return "\"\(escaped)\""
    }
}
