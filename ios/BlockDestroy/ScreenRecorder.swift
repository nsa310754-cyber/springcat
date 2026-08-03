import ReplayKit

/// ReplayKit によるアプリ内画面録画 (フルスクリーン配信ではなく in-app recording)。
/// システムの録画許可ダイアログは初回のみ表示され、ステータスバーの録画インジケータは出ない。
/// Android 版の WebViewRecorder.java に相当。
final class ScreenRecorder {

    static let shared = ScreenRecorder()

    private(set) var isRecording = false

    private init() { }

    func start(completion: @escaping (Bool) -> Void) {
        let recorder = RPScreenRecorder.shared()
        guard recorder.isAvailable, !isRecording else {
            completion(false)
            return
        }
        recorder.startRecording { [weak self] error in
            DispatchQueue.main.async {
                self?.isRecording = (error == nil)
                completion(error == nil)
            }
        }
    }

    func stop(completion: @escaping (URL?) -> Void) {
        let recorder = RPScreenRecorder.shared()
        guard isRecording else {
            completion(nil)
            return
        }
        let name = "blockdestory_rec_" + Self.timestamp() + ".mp4"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)

        recorder.stopRecording(withOutput: url) { [weak self] error in
            DispatchQueue.main.async {
                self?.isRecording = false
                completion(error == nil ? url : nil)
            }
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: Date())
    }
}
