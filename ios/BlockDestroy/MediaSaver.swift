import Photos
import UIKit

/// ゲームがエクスポートする blob/data (スクリーンショット・録画) を端末へ保存する。
/// Android 版の MediaSaver.java に相当。
/// 画像・動画は写真アプリへ、それ以外はアプリの書類フォルダ (Files アプリから参照可) へ保存する。
enum MediaSaver {

    static func save(name: String, mime: String, base64: String, completion: @escaping (Bool) -> Void) {
        guard let data = Data(base64Encoded: base64) else {
            completion(false)
            return
        }

        if mime.hasPrefix("image/") {
            saveImageToPhotos(data: data, completion: completion)
        } else if mime.hasPrefix("video/") {
            saveVideoDataToPhotos(data: data, suggestedName: name, completion: completion)
        } else {
            saveToDocuments(data: data, name: name, completion: completion)
        }
    }

    static func saveVideoFile(at url: URL, completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
        }, completionHandler: { ok, _ in
            DispatchQueue.main.async { completion(ok) }
        })
    }

    private static func saveImageToPhotos(data: Data, completion: @escaping (Bool) -> Void) {
        guard let image = UIImage(data: data) else {
            completion(false)
            return
        }
        requestPhotosAuthorization { authorized in
            guard authorized else { completion(false); return }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            }, completionHandler: { ok, _ in
                DispatchQueue.main.async { completion(ok) }
            })
        }
    }

    private static func saveVideoDataToPhotos(data: Data, suggestedName: String, completion: @escaping (Bool) -> Void) {
        let tmpURL = FileManager.default.temporaryDirectory.appendingPathComponent(suggestedName)
        do {
            try data.write(to: tmpURL, options: .atomic)
        } catch {
            completion(false)
            return
        }
        requestPhotosAuthorization { authorized in
            guard authorized else { completion(false); return }
            saveVideoFile(at: tmpURL) { ok in
                try? FileManager.default.removeItem(at: tmpURL)
                completion(ok)
            }
        }
    }

    private static func saveToDocuments(data: Data, name: String, completion: @escaping (Bool) -> Void) {
        do {
            let dir = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let url = dir.appendingPathComponent(name)
            try data.write(to: url, options: .atomic)
            completion(true)
        } catch {
            completion(false)
        }
    }

    private static func requestPhotosAuthorization(_ completion: @escaping (Bool) -> Void) {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        switch status {
        case .authorized, .limited:
            completion(true)
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { newStatus in
                DispatchQueue.main.async { completion(newStatus == .authorized || newStatus == .limited) }
            }
        default:
            completion(false)
        }
    }
}
