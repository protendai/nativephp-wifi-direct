<?php

namespace Protendai\WifiDirect;

/**
 * Wi-Fi Direct for NativePHP Mobile (Android). Every method is a thin,
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
     * @return array{supported: bool, enabled: bool, fiveGhz: bool, permission: bool, connected: bool, isGroupOwner: bool, groupOwnerIp: string}
     */
    public function status(): array
    {
        $defaults = [
            'supported' => false,
            'enabled' => false,
            'fiveGhz' => false,
            'permission' => false,
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
     * Host a group. Prefers $band ('5ghz', '2.4ghz' or 'auto'); a 5 GHz
     * request falls back to auto when the chipset can't do it.
     * Answers with GroupCreated or ConnectionFailed.
     */
    public function createGroup(string $band = '5ghz'): void
    {
        $this->call('WifiDirect.CreateGroup', ['band' => $band]);
    }

    /**
     * Join a group silently (no system invitation dialog).
     * Answers with Connected or ConnectionFailed (after 30s at most).
     */
    public function connect(string $networkName, string $passphrase, string $band = 'auto'): void
    {
        $this->call('WifiDirect.Connect', [
            'networkName' => $networkName,
            'passphrase' => $passphrase,
            'band' => $band,
        ]);
    }

    /**
     * Leave or tear down the group; the device returns to its normal Wi-Fi.
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
     * Parse a scanned pairing payload; null when the text isn't one.
     *
     * @return array{networkName: string, passphrase: string, extra: array<string, mixed>}|null
     */
    public function parsePairing(string $text): ?array
    {
        $data = json_decode($text, true);

        if (! is_array($data) || ($data['wd'] ?? null) !== 1 || ! is_string($data['n'] ?? null) || ! is_string($data['p'] ?? null)) {
            return null;
        }

        $extra = $data;
        unset($extra['wd'], $extra['n'], $extra['p']);

        return ['networkName' => $data['n'], 'passphrase' => $data['p'], 'extra' => $extra];
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
