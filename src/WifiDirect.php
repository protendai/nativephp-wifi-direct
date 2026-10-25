<?php

namespace Protendai\WifiDirect;

/**
 * Wi-Fi Direct for NativePHP Mobile (Android hosts and joins; iOS joins an
 * Android phone's group as a Wi-Fi client). Every method is a thin,
 * synchronous bridge call; slow work (permission prompts, creating or
 * joining a group, scanning a QR code) finishes later as an event under
 * Protendai\WifiDirect\Events.
 *
 * Typical pairing: one device createGroup()s and shows qrCode() of
 * pairingPayload(); the other scanQr()s it and connect()s. Afterwards the
 * owner is reachable at 192.168.49.1 with ordinary sockets/HTTP.
 */
class WifiDirect
{
    public const OWNER_IP = '192.168.49.1';

    /**
     * Capabilities and current state.
     *
     * @return array{supported: bool, canHost: bool, enabled: bool, fiveGhz: bool, permission: bool, wifiJoined: bool, connected: bool, isGroupOwner: bool, groupOwnerIp: string}
     */
    public function status(): array
    {
        $defaults = [
            'supported' => false,
            // Can host a group (show a code). iOS can only join (scan an Android's code).
            'canHost' => true,
            'enabled' => false,
            'fiveGhz' => false,
            'permission' => false,
            // Wi-Fi joined to a network; assumed true where the platform doesn't say.
            'wifiJoined' => true,
            'connected' => false,
            'isGroupOwner' => false,
            'groupOwnerIp' => '',
        ];

        return array_merge($defaults, array_intersect_key($this->decode($this->call('WifiDirect.Status')), $defaults));
    }

    public function isSupported(): bool
    {
        return $this->status()['supported'];
    }

    /**
     * Prompt for "Nearby Wi-Fi devices" (Android 13+) or location (older).
     * Answers with a PermissionResult event (immediately if already granted).
     */
    public function requestPermission(): void
    {
        $this->call('WifiDirect.RequestPermission');
    }

    /**
     * Host a group (Android). Cancels anything still in flight first.
     * Prefers $band ('5ghz', '2.4ghz' or 'auto'); a 5 GHz
     * request falls back to auto when the chipset can't do it.
     * Answers with GroupCreated or ConnectionFailed within $timeoutSeconds.
     * ConnectionFailed reason "needs network": this phone can only host while
     * Wi-Fi is joined to a network, so let the other phone host instead.
     */
    public function createGroup(string $band = '5ghz', int $timeoutSeconds = 20): void
    {
        $this->call('WifiDirect.CreateGroup', ['band' => $band, 'timeoutSeconds' => $timeoutSeconds]);
    }

    /**
     * Join a group silently (no system invitation dialog). Cancels anything
     * still in flight (another join, createGroup()) first.
     * Answers with Connected or ConnectionFailed (after 30 s at most).
     *
     * Returns false, and fires no event, when the network name or passphrase
     * is malformed (see isValidNetworkName() / isValidPassphrase()) or the
     * device refused the call.
     */
    public function connect(string $networkName, string $passphrase, string $band = 'auto'): bool
    {
        if (! self::isValidNetworkName($networkName) || ! self::isValidPassphrase($passphrase)) {
            return false;
        }

        $result = $this->call('WifiDirect.Connect', [
            'networkName' => $networkName,
            'passphrase' => $passphrase,
            'band' => $band,
        ]);

        // Outside the app (no bridge) there is nothing to refuse.
        return $result === null || (bool) ($this->decode($result)['ok'] ?? false);
    }

    /**
     * Leave or tear down the group and cancel anything in flight (a join
     * waiting for its timeout, createGroup() still trying); the device
     * returns to its normal Wi-Fi. Always answers with Disconnected.
     */
    public function disconnect(): void
    {
        $this->call('WifiDirect.Disconnect');
    }

    /**
     * Write $text as a QR code PNG to $dest.
     */
    public function qrCode(string $text, string $dest, int $size = 640, string $foreground = '#000000', string $background = '#FFFFFF'): bool
    {
        $result = $this->decode($this->call('WifiDirect.QrCode', [
            'text' => $text,
            'dest' => $dest,
            'size' => $size,
            'foreground' => $foreground,
            'background' => $background,
        ]));

        return (bool) ($result['ok'] ?? false);
    }

    /**
     * Open the system code scanner (Google Play services; no camera
     * permission needed). Answers with QrScanned or QrScanCancelled.
     */
    public function scanQr(): void
    {
        $this->call('WifiDirect.ScanQr');
    }

    /**
     * The QR payload for a created group, plus any app data the joining
     * device needs (e.g. a port or display name).
     *
     * @param  array<string, mixed>  $extra
     */
    public function pairingPayload(string $networkName, string $passphrase, array $extra = []): string
    {
        return json_encode(['wd' => 1, 'n' => $networkName, 'p' => $passphrase] + $extra, JSON_UNESCAPED_SLASHES) ?: '';
    }

    /**
     * Parse a scanned pairing payload; null when the text isn't one, or
     * when its network name or passphrase is malformed (so a hostile or
     * damaged code can never reach connect()).
     *
     * @return array{networkName: string, passphrase: string, extra: array<string, mixed>}|null
     */
    public function parsePairing(string $text): ?array
    {
        $data = json_decode($text, true);

        if (! is_array($data) || ($data['wd'] ?? null) !== 1 || ! is_string($data['n'] ?? null) || ! is_string($data['p'] ?? null)) {
            return null;
        }

        if (! self::isValidNetworkName($data['n']) || ! self::isValidPassphrase($data['p'])) {
            return null;
        }

        $extra = $data;
        unset($extra['wd'], $extra['n'], $extra['p']);

        return ['networkName' => $data['n'], 'passphrase' => $data['p'], 'extra' => $extra];
    }

    /**
     * A Wi-Fi Direct group name: "DIRECT-" plus two letters or digits, at
     * most 32 bytes of UTF-8. Android refuses (throws on) anything else.
     */
    public static function isValidNetworkName(string $networkName): bool
    {
        return strlen($networkName) <= 32
            && preg_match('/^DIRECT-[a-zA-Z0-9]{2}[^\n\r\x{85}\x{2028}\x{2029}]*$/uD', $networkName) === 1;
    }

    /**
     * A WPA2 passphrase: 8 to 63 printable ASCII characters.
     */
    public static function isValidPassphrase(string $passphrase): bool
    {
        return preg_match('/^[\x20-\x7E]{8,63}$/D', $passphrase) === 1;
    }

    /**
     * @return array<string, mixed>
     */
    private function decode(?string $result): array
    {
        $decoded = $result !== null ? json_decode($result, true) : null;

        return is_array($decoded) ? $decoded : [];
    }

    /**
     * @param  array<string, mixed>  $params
     */
    private function call(string $method, array $params = []): ?string
    {
        if (! function_exists('nativephp_call')) {
            return null;
        }

        return nativephp_call($method, json_encode($params) ?: '{}');
    }
}
