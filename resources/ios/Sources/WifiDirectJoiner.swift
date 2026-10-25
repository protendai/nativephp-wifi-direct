import Foundation
import Network
import NetworkExtension

/// Joins an Android phone's Wi-Fi Direct group as a plain Wi-Fi client.
///
/// All state lives on `queue`. Every connect()/disconnect() bumps `attempt`,
/// so callbacks of an abandoned join can tell they are stale.
final class WifiDirectJoiner {

    static let shared = WifiDirectJoiner()

    static let ownerIp = "192.168.49.1"
    private static let groupPrefix = "192.168.49."
    private static let joinTimeout: TimeInterval = 20
    private static let linkCheckInterval: TimeInterval = 2

    private let queue = DispatchQueue(label: "com.protendai.wifidirect.join")
    /// SSID of the join in progress or joined, recorded before the system
    /// prompt so a cancel during the prompt can still remove it.
    private var targetSsid: String?
    private var connected = false
    private var attempt = 0
    private var monitor: NWPathMonitor?
    private var missedChecks = 0

    // MARK: Validation

    /// Why credentials would be refused, or nil when usable. Same rules as
    /// the Android side: a DIRECT-xy name of at most 32 bytes and a WPA2
    /// passphrase of 8 to 63 printable ASCII characters.
    static func credentialsError(networkName: String, passphrase: String) -> String? {
        // Android's check is Java's "^DIRECT-[a-zA-Z0-9]{2}.*", whose "." stops at line terminators.
        let lineTerminators: Set<UInt32> = [0x0A, 0x0D, 0x85, 0x2028, 0x2029]
        if !networkName.hasPrefix("DIRECT-")
            || networkName.range(of: "^DIRECT-[a-zA-Z0-9]{2}", options: .regularExpression) == nil
            || networkName.unicodeScalars.contains(where: { lineTerminators.contains($0.value) }) {
            return "network name must start with DIRECT-xy"
        }
        if networkName.utf8.count > 32 {
            return "network name is longer than 32 bytes"
        }
        if passphrase.count < 8 || passphrase.count > 63 {
            return "passphrase must be 8 to 63 characters"
        }
        if passphrase.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value > 0x7E }) {
            return "passphrase must be printable ASCII"
        }
        return nil
    }

    // MARK: State

    func status() -> [String: Any] {
        let isConnected = queue.sync { connected }
        let linked = isConnected && Self.groupAddress() != nil
        return [
            "supported": true,
            "canHost": false,
            "enabled": true,
            "fiveGhz": true,
            "permission": true,
            "wifiJoined": Self.wifiAddress() != nil,
            "connected": linked,
            "isGroupOwner": false,
            "groupOwnerIp": linked ? Self.ownerIp : "",
        ]
    }

    // MARK: Joining

    func connect(networkName: String, passphrase: String) {
        queue.async {
            // A new join replaces whatever we had (and reports the end of its link).
            self.leaveCurrent(removeConfiguration: self.targetSsid != networkName, reportLink: true)
            self.attempt += 1
            let attempt = self.attempt
            self.targetSsid = networkName

            let configuration = NEHotspotConfiguration(ssid: networkName, passphrase: passphrase, isWEP: false)
            configuration.joinOnce = true

            NEHotspotConfigurationManager.shared.apply(configuration) { error in
                self.queue.async {
                    let joined = error == nil || Self.isAlreadyAssociated(error)

                    guard attempt == self.attempt else {
                        // Cancelled while the system prompt was up. If the join went
                        // ahead anyway, leave that network again.
                        if joined, self.targetSsid != networkName {
                            NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: networkName)
                        }
                        return
                    }

                    if !joined, let error = error as NSError? {
                        self.targetSsid = nil
                        Self.fail(Self.reason(for: error), detail: "\(error.domain) \(error.code)")
                        return
                    }

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
            startMonitoring(attempt: attempt)
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
            // Joined the network but never got an address on it: leave it again.
            self.attempt += 1
            if let ssid = targetSsid {
                NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
            }
            targetSsid = nil
            Self.fail("timeout", detail: "joined but got no 192.168.49.x address")
            return
        }

        queue.asyncAfter(deadline: .now() + 0.5) { self.waitForAddress(attempt: attempt, deadline: deadline) }
    }

    func disconnect() {
        queue.async {
            self.leaveCurrent(removeConfiguration: true, reportLink: false)
            self.attempt += 1
            // Always answer, link or not, so callers can rely on it.
            Self.dispatch("Disconnected", [:])
        }
    }

    /// Stop monitoring and forget the current network. With `reportLink`,
    /// a link we had announced with Connected gets its Disconnected. On queue.
    private func leaveCurrent(removeConfiguration: Bool, reportLink: Bool) {
        stopMonitoring()
        if removeConfiguration, let ssid = targetSsid {
            NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
        }
        targetSsid = nil
        let wasConnected = connected
        connected = false
        if wasConnected, reportLink {
            Self.dispatch("Disconnected", [:])
        }
    }

    // MARK: Link loss

    /// Watch the Wi-Fi path and poll for our group address, so a link that
    /// drops (the host left, out of range) is reported as Disconnected.
    private func startMonitoring(attempt: Int) {
        stopMonitoring()
        missedChecks = 0

        let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
        monitor.pathUpdateHandler = { [weak self] _ in
            // Give DHCP a moment after a path change before judging.
            self?.queue.asyncAfter(deadline: .now() + 1) { self?.checkLink(attempt: attempt) }
        }
        monitor.start(queue: queue)
        self.monitor = monitor
        scheduleLinkCheck(attempt: attempt)
    }

    private func stopMonitoring() {
        monitor?.cancel()
        monitor = nil
    }

    private func scheduleLinkCheck(attempt: Int) {
        queue.asyncAfter(deadline: .now() + Self.linkCheckInterval) { [weak self] in
            guard let self = self, attempt == self.attempt, self.connected else { return }
            self.checkLink(attempt: attempt)
            self.scheduleLinkCheck(attempt: attempt)
        }
    }

    private func checkLink(attempt: Int) {
        guard attempt == self.attempt, connected else { return }

        if Self.groupAddress() != nil {
            missedChecks = 0
            return
        }

        // Two misses in a row, so a DHCP renewal doesn't count as a loss.
        missedChecks += 1
        guard missedChecks >= 2 else { return }

        self.attempt += 1
        stopMonitoring()
        if let ssid = targetSsid {
            NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
        }
        targetSsid = nil
        connected = false
        Self.dispatch("Disconnected", [:])
    }

    // MARK: Errors

    private static func isAlreadyAssociated(_ error: Error?) -> Bool {
        guard let error = error as NSError? else { return false }
        return error.domain == NEHotspotConfigurationErrorDomain
            && error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue
    }

    private static func reason(for error: NSError) -> String {
        guard error.domain == NEHotspotConfigurationErrorDomain,
              let code = NEHotspotConfigurationError(rawValue: error.code) else { return "error" }
        switch code {
        case .userDenied: return "declined"
        case .invalidSSID, .invalidWPAPassphrase, .invalid: return "invalid"
        case .pending: return "busy"
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
