import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import UIKit

// iOS has no public Wi-Fi Direct API, so an iPhone can't host a group. It
// can join an Android phone's group as an ordinary Wi-Fi client, though:
// the group is a WPA2 network whose SSID and passphrase are in the pairing
// QR code, and NEHotspotConfiguration joins it (needs the Hotspot
// Configuration entitlement, declared in nativephp.json).
//
// Status reports canHost=false so apps send the user to scan the other
// phone's code; CreateGroup fails with "cannot host".
enum WifiDirectFunctions {

    class Status: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: WifiDirectJoiner.shared.status())
        }
    }

    /// Nothing to ask for: joining needs no runtime permission, and the
    /// scanner asks for the camera itself.
    class RequestPermission: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            WifiDirectJoiner.dispatch("PermissionResult", ["granted": true, "permanentlyDenied": false])
            return BridgeResponse.success(data: ["ok": true, "granted": true])
        }
    }

    class CreateGroup: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            WifiDirectJoiner.fail("cannot host", detail: "iOS can only join a group; let the other device show the code")
            return BridgeResponse.success(data: ["ok": false])
        }
    }

    /// Params: networkName, passphrase (band is ignored on iOS). Malformed
    /// credentials are refused here ({ok: false, error}) and no event follows.
    class Connect: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let name = parameters["networkName"] as? String,
                  let passphrase = parameters["passphrase"] as? String else {
                return BridgeResponse.success(data: ["ok": false, "error": "networkName and passphrase are required"])
            }
            if let error = WifiDirectJoiner.credentialsError(networkName: name, passphrase: passphrase) {
                return BridgeResponse.success(data: ["ok": false, "error": error])
            }
            WifiDirectJoiner.shared.connect(networkName: name, passphrase: passphrase)
            return BridgeResponse.success(data: ["ok": true])
        }
    }

    /// Leave the group (or cancel a join in progress). Always answers with Disconnected.
    class Disconnect: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            WifiDirectJoiner.shared.disconnect()
            return BridgeResponse.success(data: ["ok": true])
        }
    }

    /// Full-screen camera QR scanner. Answers with QrScanned {text} or QrScanCancelled {reason}.
    class ScanQr: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            DispatchQueue.main.async { QrScannerViewController.present() }
            return BridgeResponse.success(data: ["ok": true])
        }
    }

    /// Render text as a QR code PNG at `dest`. Params: text, dest, size (px),
    /// foreground / background ("#RRGGBB" or "#AARRGGBB"). Synchronous.
    class QrCode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let text = parameters["text"] as? String, let dest = parameters["dest"] as? String else {
                return BridgeResponse.success(data: ["ok": false])
            }
            let size = min(max((parameters["size"] as? NSNumber)?.intValue ?? 640, 64), 4096)
            let foreground = QrRenderer.color(hex: parameters["foreground"] as? String) ?? CGColor(red: 0, green: 0, blue: 0, alpha: 1)
            let background = QrRenderer.color(hex: parameters["background"] as? String) ?? CGColor(red: 1, green: 1, blue: 1, alpha: 1)

            guard let png = QrRenderer.png(text: text, size: size, foreground: foreground, background: background) else {
                return BridgeResponse.success(data: ["ok": false])
            }

            let url = URL(fileURLWithPath: dest)
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let ok = (try? png.write(to: url)) != nil
            return BridgeResponse.success(data: ["ok": ok])
        }
    }
}

// MARK: - QR rendering

/// Draws a QR code with sharp modules: each module becomes an integer number
/// of pixels (nearest neighbour, no interpolation), centred on a `size` x
/// `size` canvas in the requested colours, like ZXing does on Android.
enum QrRenderer {

    static func png(text: String, size: Int, foreground: CGColor, background: CGColor) -> Data? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let modulesImage = CIContext().createCGImage(output, from: output.extent) else { return nil }

        // One pixel per module: read the grid back.
        let modules = modulesImage.width
        guard modules > 0, modulesImage.height == modules else { return nil }
        var grid = [UInt8](repeating: 255, count: modules * modules)
        let readable = grid.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: modules, height: modules, bitsPerComponent: 8, bytesPerRow: modules,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            context.interpolationQuality = .none
            context.draw(modulesImage, in: CGRect(x: 0, y: 0, width: modules, height: modules))
            return true
        }
        guard readable else { return nil }

        let side = max(size, modules)
        let scale = side / modules
        let offset = (side - modules * scale) / 2

        guard let canvas = CGContext(
            data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        canvas.setFillColor(background)
        canvas.fill(CGRect(x: 0, y: 0, width: side, height: side))
        canvas.setFillColor(foreground)
        for row in 0..<modules {
            for column in 0..<modules where grid[row * modules + column] < 128 {
                // Bitmap row 0 is the top of the image; Core Graphics' origin is bottom-left.
                canvas.fill(CGRect(x: offset + column * scale, y: side - offset - (row + 1) * scale, width: scale, height: scale))
            }
        }

        guard let image = canvas.makeImage() else { return nil }
        return UIImage(cgImage: image).pngData()
    }

    /// "#RRGGBB" or "#AARRGGBB" (Android's Color.parseColor format).
    static func color(hex: String?) -> CGColor? {
        guard var digits = hex?.trimmingCharacters(in: .whitespaces), digits.hasPrefix("#") else { return nil }
        digits.removeFirst()
        guard digits.count == 6 || digits.count == 8, let value = UInt64(digits, radix: 16) else { return nil }

        let alpha = digits.count == 8 ? CGFloat((value >> 24) & 0xFF) / 255 : 1
        let red = CGFloat((value >> 16) & 0xFF) / 255
        let green = CGFloat((value >> 8) & 0xFF) / 255
        let blue = CGFloat(value & 0xFF) / 255
        return CGColor(red: red, green: green, blue: blue, alpha: alpha)
    }
}
