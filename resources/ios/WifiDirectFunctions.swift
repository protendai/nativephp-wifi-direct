import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import UIKit

// iOS has no public Wi-Fi Direct API (Apple's peer links use AWDL via
// Multipeer Connectivity). Status reports supported=false so apps can hide
// the feature; QrCode works so pairing screens still render a code.
enum WifiDirectFunctions {

    class Status: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: [
                "supported": false, "enabled": false, "fiveGhz": false, "permission": false,
                "connected": false, "isGroupOwner": false, "groupOwnerIp": "",
            ])
        }
    }

    class RequestPermission: Unsupported {}
    class CreateGroup: Unsupported {}
    class Connect: Unsupported {}
    class Disconnect: Unsupported {}
    class ScanQr: Unsupported {}

    class Unsupported: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ["ok": false, "reason": "unsupported"])
        }
    }

    /// Render text as a QR code PNG at `dest`.
    class QrCode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let text = parameters["text"] as? String, let dest = parameters["dest"] as? String else {
                return BridgeResponse.success(data: ["ok": false])
            }
            let size = CGFloat((parameters["size"] as? NSNumber)?.doubleValue ?? 640)

            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(text.utf8)
            filter.correctionLevel = "M"
            guard let output = filter.outputImage else { return BridgeResponse.success(data: ["ok": false]) }

            let scale = size / output.extent.width
            let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent),
                  let png = UIImage(cgImage: cgImage).pngData() else {
                return BridgeResponse.success(data: ["ok": false])
            }

            let url = URL(fileURLWithPath: dest)
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let ok = (try? png.write(to: url)) != nil
            return BridgeResponse.success(data: ["ok": ok])
        }
    }
}
