import AVFoundation
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import NetworkExtension
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
            WifiDirectJoiner.dispatch("PermissionResult", ["granted": true])
            return BridgeResponse.success(data: ["ok": true, "granted": true])
        }
    }

    class CreateGroup: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            WifiDirectJoiner.fail("cannot host", detail: "iOS can only join a group; let the other phone show the code")
            return BridgeResponse.success(data: ["ok": false])
        }
    }

    /// Params: networkName, passphrase (band is ignored on iOS).
    class Connect: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let name = parameters["networkName"] as? String, !name.isEmpty,
                  let passphrase = parameters["passphrase"] as? String else {
                WifiDirectJoiner.fail("error", detail: "missing networkName or passphrase")
                return BridgeResponse.success(data: ["ok": false])
            }
            WifiDirectJoiner.shared.connect(networkName: name, passphrase: passphrase)
            return BridgeResponse.success(data: ["ok": true])
        }
    }

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

// MARK: - Joining

final class WifiDirectJoiner {

    static let shared = WifiDirectJoiner()

    static let ownerIp = "192.168.49.1"
    private static let groupPrefix = "192.168.49."
    private static let joinTimeout: TimeInterval = 20

    private let queue = DispatchQueue(label: "com.protendai.wifidirect.join")
    private var joinedSsid: String?
    private var connected = false
    private var attempt = 0

    func status() -> [String: Any] {
        let ip = Self.groupAddress()
        return [
            "supported": true,
            "canHost": false,
            "enabled": true,
            "fiveGhz": true,
            "permission": true,
            "wifiJoined": Self.wifiAddress() != nil,
            "connected": connected && ip != nil,
            "isGroupOwner": false,
            "groupOwnerIp": connected ? Self.ownerIp : "",
        ]
    }

    func connect(networkName: String, passphrase: String) {
        queue.async {
            self.attempt += 1
            let attempt = self.attempt

            let configuration = NEHotspotConfiguration(ssid: networkName, passphrase: passphrase, isWEP: false)
            configuration.joinOnce = true

            NEHotspotConfigurationManager.shared.apply(configuration) { error in
                self.queue.async {
                    guard attempt == self.attempt else { return }

                    if let error = error as NSError?,
                       !(error.domain == NEHotspotConfigurationErrorDomain
                         && error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue) {
                        Self.fail(Self.reason(for: error), detail: "\(error.domain) \(error.code): \(error.localizedDescription)")
                        return
                    }

                    self.joinedSsid = networkName
                    self.waitForAddress(attempt: attempt, deadline: Date().addingTimeInterval(Self.joinTimeout))
                }
            }
        }
    }

    /// The join callback fires before DHCP finishes; wait for a 192.168.49.x address.
    private func waitForAddress(attempt: Int, deadline: Date) {
        guard attempt == self.attempt else { return }

        if Self.groupAddress() != nil {
            connected = true
            Self.dispatch("Connected", [
                "connected": true,
                "isGroupOwner": false,
                "groupOwnerIp": Self.ownerIp,
                "clients": 0,
                "frequency": 0,
            ])
            return
        }

        if Date() >= deadline {
            Self.fail("timeout", detail: "joined but got no 192.168.49.x address")
            return
        }

        queue.asyncAfter(deadline: .now() + 0.5) { self.waitForAddress(attempt: attempt, deadline: deadline) }
    }

    func disconnect() {
        queue.async {
            self.attempt += 1
            let wasConnected = self.connected
            if let ssid = self.joinedSsid {
                NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
            }
            self.joinedSsid = nil
            self.connected = false
            if wasConnected {
                Self.dispatch("Disconnected", [:])
            }
        }
    }

    private static func reason(for error: NSError) -> String {
        guard error.domain == NEHotspotConfigurationErrorDomain,
              let code = NEHotspotConfigurationError(rawValue: error.code) else { return "error" }
        switch code {
        case .userDenied: return "declined"
        case .invalidSSID, .invalidWPAPassphrase, .invalid: return "error"
        case .pending: return "busy"
        case .joinOnceNotSupported, .systemConfiguration, .internal, .unknown: return "error"
        default: return "error"
        }
    }

    // MARK: Addresses

    /// Our IPv4 on the Wi-Fi Direct group (192.168.49.x), if joined.
    static func groupAddress() -> String? {
        return ipv4Addresses(interfacePrefix: "en").first { $0.hasPrefix(groupPrefix) }
    }

    /// Any IPv4 on the Wi-Fi interface (en0), i.e. joined to some network.
    static func wifiAddress() -> String? {
        return ipv4Addresses(interfacePrefix: "en0").first
    }

    private static func ipv4Addresses(interfacePrefix: String) -> [String] {
        var addresses: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard let addr = entry.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET),
                  (flags & IFF_UP) != 0,
                  String(cString: entry.pointee.ifa_name).hasPrefix(interfacePrefix) else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                addresses.append(String(cString: host))
            }
        }
        return addresses
    }

    // MARK: Events

    static func fail(_ reason: String, detail: String) {
        NSLog("WifiDirect: failed: \(reason) (\(detail))")
        dispatch("ConnectionFailed", ["reason": reason, "detail": detail])
    }

    static func dispatch(_ event: String, _ payload: [String: Any]) {
        DispatchQueue.main.async {
            LaravelBridge.shared.send?("Protendai\\WifiDirect\\Events\\\(event)", payload)
        }
    }
}

// MARK: - QR scanner

final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var finished = false

    static func present() {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              var top = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
                ?? scene.windows.first?.rootViewController else {
            WifiDirectJoiner.dispatch("QrScanCancelled", ["reason": "no window"])
            return
        }
        while let next = top.presentedViewController { top = next }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            show(on: top)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        show(on: top)
                    } else {
                        WifiDirectJoiner.dispatch("QrScanCancelled", ["reason": "camera denied"])
                    }
                }
            }
        default:
            WifiDirectJoiner.dispatch("QrScanCancelled", ["reason": "camera denied"])
        }
    }

    private static func show(on presenter: UIViewController) {
        let scanner = QrScannerViewController()
        scanner.modalPresentationStyle = .fullScreen
        presenter.present(scanner, animated: true)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            finish(cancelReason: "no camera")
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            finish(cancelReason: "no camera")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview

        addOverlay()
    }

    private func addOverlay() {
        let hint = UILabel()
        hint.text = "Point at the code on the other phone"
        hint.textColor = .white
        hint.font = .preferredFont(forTextStyle: .headline)
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.translatesAutoresizingMaskIntoConstraints = false

        let frame = UIView()
        frame.layer.borderColor = UIColor.white.cgColor
        frame.layer.borderWidth = 3
        frame.layer.cornerRadius = 24
        frame.translatesAutoresizingMaskIntoConstraints = false

        var config = UIButton.Configuration.filled()
        config.title = "Cancel"
        config.cornerStyle = .capsule
        config.baseBackgroundColor = UIColor.white.withAlphaComponent(0.9)
        config.baseForegroundColor = .black
        let cancel = UIButton(configuration: config)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        cancel.addAction(UIAction { [weak self] _ in self?.finish(cancelReason: "cancelled") }, for: .touchUpInside)

        view.addSubview(frame)
        view.addSubview(hint)
        view.addSubview(cancel)

        NSLayoutConstraint.activate([
            frame.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            frame.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            frame.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.7),
            frame.heightAnchor.constraint(equalTo: frame.widthAnchor),

            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hint.bottomAnchor.constraint(equalTo: frame.topAnchor, constant: -24),

            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            cancel.widthAnchor.constraint(equalToConstant: 160),
            cancel.heightAnchor.constraint(equalToConstant: 48),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let session = self.session
        DispatchQueue.global(qos: .userInitiated).async {
            if !session.isRunning { session.startRunning() }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let session = self.session
        DispatchQueue.global(qos: .userInitiated).async {
            if session.isRunning { session.stopRunning() }
        }
        // Swiped away or dismissed some other way: still answer the caller.
        if !finished {
            finished = true
            WifiDirectJoiner.dispatch("QrScanCancelled", ["reason": "cancelled"])
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !finished,
              let code = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let text = code.stringValue else { return }
        finished = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        WifiDirectJoiner.dispatch("QrScanned", ["text": text])
        dismiss(animated: true)
    }

    private func finish(cancelReason: String) {
        guard !finished else { return }
        finished = true
        WifiDirectJoiner.dispatch("QrScanCancelled", ["reason": cancelReason])
        if presentingViewController != nil {
            dismiss(animated: true)
        }
    }
}
