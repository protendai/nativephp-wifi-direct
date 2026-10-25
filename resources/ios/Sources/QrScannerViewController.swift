import AVFoundation
import UIKit

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
