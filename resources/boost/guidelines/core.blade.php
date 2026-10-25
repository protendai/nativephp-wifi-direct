## protendai/nativephp-wifi-direct

Wi-Fi Direct for NativePHP Mobile v4: phone-to-phone networking with no router. One device hosts a group (a private Wi-Fi network), the other joins it; the host is always reachable at `192.168.49.1` (`Protendai\WifiDirect\WifiDirect::OWNER_IP`). Pairing is by QR code, generated and scanned by the plugin. Android 10+ hosts and joins; iOS 15+ can only join an Android phone's group (`status()['canHost']` is false there).

### Methods (facade `Protendai\WifiDirect\Facades\WifiDirect`)

Every call returns immediately; slow work answers with an event.

- `WifiDirect::status(): array` keys `supported`, `canHost`, `enabled`, `fiveGhz`, `permission`, `wifiJoined`, `connected`, `isGroupOwner`, `groupOwnerIp` (always all present). `isSupported(): bool`.
- `WifiDirect::requestPermission(): void` → `PermissionResult` (immediately when already granted).
- `WifiDirect::createGroup(string $band = '5ghz', int $timeoutSeconds = 20): void` (Android) → `GroupCreated` or `ConnectionFailed` within the timeout. Bands: `5ghz`, `2.4ghz`, `auto`.
- `WifiDirect::connect(string $networkName, string $passphrase, string $band = 'auto'): bool` → `Connected` or `ConnectionFailed`. Returns false and fires NO event for malformed credentials.
- `WifiDirect::disconnect(): void` → always `Disconnected`, link or not. Cancels anything in flight.
- `WifiDirect::qrCode(string $text, string $dest, int $size = 640, string $foreground = '#000000', string $background = '#FFFFFF'): bool` writes a PNG.
- `WifiDirect::scanQr(): void` → `QrScanned` or `QrScanCancelled`.
- `WifiDirect::pairingPayload(string $networkName, string $passphrase, array $extra = []): string` and `WifiDirect::parsePairing(string $text): ?array` (`networkName`, `passphrase`, `extra`; null for foreign or malformed codes). Put app data (port, display name) in `$extra`.
- `WifiDirect::isValidNetworkName(string)`, `WifiDirect::isValidPassphrase(string)`: `DIRECT-xy…` up to 32 bytes; 8–63 printable ASCII.
- `createGroup()`, `connect()` and `disconnect()` each cancel the previous operation; nothing from a cancelled one fires later.

### Events (`Protendai\WifiDirect\Events\*`; `#[On(...)]` from `Native\Mobile\Attributes\On` in EDGE components, `#[OnNative(...)]` in Livewire; parameters bind by name, give them defaults)

- `PermissionResult`: `bool $granted`, `?string $reason` (`denied`, `permanently denied`, `approximate`, `interrupted`), `bool $permanentlyDenied`.
- `GroupCreated`: `string $networkName`, `string $passphrase`, `string $ownerIp`, `int $frequency`, `string $band`.
- `Connected`: `bool $isGroupOwner`, `?string $groupOwnerIp`, `int $clients`, `int $frequency`. The host gets it when a member joins (may not happen for an iPhone member on every Android build: let the joiner announce itself to `192.168.49.1`).
- `Disconnected`: no payload. After `disconnect()`, when the link drops, or when a new operation replaces a reported link.
- `ConnectionFailed`: `?string $reason` (`permission`, `unsupported`, `wifi off`, `location off`, `busy`, `needs network`, `group not ready`, `timeout`, `invalid`, `error`, `cannot host` (iOS), `declined` (iOS)), `?string $detail` (diagnostic, no secrets).
- `QrScanned`: `string $text`. `QrScanCancelled`: `?string $reason`.

### Gotchas

- Check `status()['permission']` before hosting or scanning; call `requestPermission()` and continue in the `PermissionResult` handler.
- `needs network`: that phone can only host while joined to a Wi-Fi network; offer the other direction (the other phone hosts).
- On iOS, call `scanQr()` + `connect()` only; never `createGroup()`.
- Call `disconnect()` when leaving the flow, on both devices. The link survives screen navigation; read `status()['connected']` on mount to pick it up again.
- Android 10–12 needs Location switched on. The plugin declares FINE and COARSE location with `maxSdkVersion="32"`; an app or plugin declaring them without `maxSdkVersion` conflicts (see README).
- NativePHP core logs event payloads in debug builds, and `GroupCreated` contains the passphrase.
- Native code: rebuild with `php artisan native:run` after installing or updating.

### JavaScript (Inertia / Vue / React)

`import { status, requestPermission, createGroup, connect, disconnect, qrCode, scanQr, pairingPayload, parsePairing, onWifiDirect, Events } from '@protendai/nativephp-wifi-direct'` (from `vendor/protendai/nativephp-wifi-direct/resources/js/index.js`). `onWifiDirect(Events.Connected, (payload) => …)` returns an unsubscribe function.

### Testing

`Native::fakeBridge()->withWifiDirect(supported: true, permission: true, fiveGhz: true, wifiJoined: true, canHost: true)`, then `assertWifiDirectHosting(?callable)`, `assertWifiDirectJoining(?callable)`, `assertWifiDirectDisconnected()`, `assertQrScanRequested()`; deliver results with `->emitNative(GroupCreated::class, [...])`.

@verbatim
<code-snippet name="Join by scanning the host's code (EDGE component)" lang="php">
use Native\Mobile\Attributes\On;
use Protendai\WifiDirect\Events\Connected;
use Protendai\WifiDirect\Events\ConnectionFailed;
use Protendai\WifiDirect\Events\QrScanned;
use Protendai\WifiDirect\Facades\WifiDirect;

public function scan(): void
{
    WifiDirect::status()['permission'] ? WifiDirect::scanQr() : WifiDirect::requestPermission();
}

#[On(QrScanned::class)]
public function onScanned(string $text = ''): void
{
    if ($pairing = WifiDirect::parsePairing($text)) {
        $this->port = (int) ($pairing['extra']['port'] ?? 8080);
        WifiDirect::connect($pairing['networkName'], $pairing['passphrase']);
    }
}

#[On(Connected::class)]
public function onConnected(bool $isGroupOwner = false, ?string $groupOwnerIp = null): void
{
    // talk to http://{$groupOwnerIp}:{$this->port}
}

#[On(ConnectionFailed::class)]
public function onFailed(?string $reason = null): void
{
    $this->error = $reason;
}
</code-snippet>

<code-snippet name="Host and show a pairing code (Android)" lang="php">
use Native\Mobile\Attributes\On;
use Protendai\WifiDirect\Events\GroupCreated;
use Protendai\WifiDirect\Facades\WifiDirect;

public function host(): void
{
    WifiDirect::createGroup('5ghz', 20);
}

#[On(GroupCreated::class)]
public function onGroupCreated(string $networkName = '', string $passphrase = ''): void
{
    $path = storage_path('app/pair.png');
    $payload = WifiDirect::pairingPayload($networkName, $passphrase, ['port' => 8080]);
    $this->qr = WifiDirect::qrCode($payload, $path) ? $path : null;
}
</code-snippet>
@endverbatim
