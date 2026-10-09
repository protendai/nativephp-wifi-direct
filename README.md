# Wi-Fi Direct for NativePHP Mobile

Phone-to-phone networking with **no router and no internet**: one device hosts a private Wi-Fi network, the other joins it, and your app talks over plain sockets/HTTP at `192.168.49.1`. Pairing is by **QR code**, built in (no camera permission, no paid scanner plugin).

- Up to several times faster than going through a home router (each byte crosses the air once, and the group can run on **5 GHz** even when the home Wi-Fi is 2.4 GHz)
- Works with **no Wi-Fi network at all**: outdoors, on a bus, at an event
- **Silent join**: no system "Accept invitation?" dialog (Android 10+)
- QR code **generation** (ZXing) and **scanning** (Google code scanner) included

| Platform | Support |
|---|---|
| Android 10+ (API 29) | Full |
| iOS | `status()` reports `supported: false`; `qrCode()` works. iOS has no public Wi-Fi Direct API. |

## Install

```bash
composer require protendai/nativephp-wifi-direct
php artisan native:plugin:register protendai/nativephp-wifi-direct
```

Rebuild the app afterwards (`php artisan native:run`). The plugin declares its own permissions:

- Android 13+: `NEARBY_WIFI_DEVICES` with `neverForLocation`, so **no location prompt**
- Android 10–12: `ACCESS_FINE_LOCATION` (Android requires it for Wi-Fi Direct on those versions)

## Quick start

**Device A (host)** creates a group and shows its code:

```php
use Native\Mobile\Attributes\OnNative;
use Protendai\WifiDirect\Events\GroupCreated;
use Protendai\WifiDirect\Events\PermissionResult;
use Protendai\WifiDirect\Facades\WifiDirect;

public function host(): void
{
    WifiDirect::status()['permission']
        ? WifiDirect::createGroup('5ghz')
        : WifiDirect::requestPermission();
}

#[OnNative(PermissionResult::class)]
public function onPermission(bool $granted): void
{
    if ($granted) {
        WifiDirect::createGroup('5ghz');
    }
}

#[OnNative(GroupCreated::class)]
public function onGroupCreated(string $networkName, string $passphrase, string $band): void
{
    $payload = WifiDirect::pairingPayload($networkName, $passphrase, ['port' => 8080]);
    WifiDirect::qrCode($payload, storage_path('app/pair.png'));
    // <native:image src="{{ storage_path('app/pair.png') }}" class="w-64 h-64" />
}
```

**Device B (joiner)** scans and connects:

```php
use Protendai\WifiDirect\Events\Connected;
use Protendai\WifiDirect\Events\QrScanned;

public function scan(): void
{
    WifiDirect::scanQr();
}

#[OnNative(QrScanned::class)]
public function onScanned(string $text): void
{
    if ($pairing = WifiDirect::parsePairing($text)) {
        WifiDirect::connect($pairing['networkName'], $pairing['passphrase']);
    }
}

#[OnNative(Connected::class)]
public function onConnected(bool $isGroupOwner, ?string $groupOwnerIp): void
{
    // The host is reachable now:
    Http::get("http://{$groupOwnerIp}:8080/hello");
}
```

Call `WifiDirect::disconnect()` when done; both devices return to their normal Wi-Fi.

## API

| Method | Returns | Notes |
|---|---|---|
| `status()` | `array` | `supported, enabled, fiveGhz, permission, connected, isGroupOwner, groupOwnerIp` |
| `isSupported()` | `bool` | Device has Wi-Fi Direct (and Android 10+) |
| `requestPermission()` | `void` | → `PermissionResult` |
| `createGroup(string $band = '5ghz')` | `void` | `'5ghz'`, `'2.4ghz'` or `'auto'`; 5 GHz falls back to auto if the chipset refuses. → `GroupCreated` / `ConnectionFailed` |
| `connect(string $networkName, string $passphrase, string $band = 'auto')` | `void` | Silent join. → `Connected` / `ConnectionFailed` (30 s timeout) |
| `disconnect()` | `void` | → `Disconnected` |
| `qrCode(string $text, string $dest, int $size = 640, string $fg = '#000000', string $bg = '#FFFFFF')` | `bool` | Writes a PNG |
| `scanQr()` | `void` | Google code scanner UI. → `QrScanned` / `QrScanCancelled` |
| `pairingPayload(string $networkName, string $passphrase, array $extra = [])` | `string` | Compact JSON for the QR code; put your own connection data in `$extra` |
| `parsePairing(string $text)` | `?array` | `['networkName', 'passphrase', 'extra']`, or `null` for foreign codes |

## Events

All under `Protendai\WifiDirect\Events`:

| Event | Properties |
|---|---|
| `PermissionResult` | `bool $granted` |
| `GroupCreated` | `string $networkName, string $passphrase, string $ownerIp, int $frequency, string $band` |
| `Connected` | `bool $isGroupOwner, ?string $groupOwnerIp, int $clients, int $frequency` (the host gets it when a member joins) |
| `Disconnected` | none |
| `ConnectionFailed` | `?string $reason`: `permission`, `unsupported`, `wifi off`, `location off`, `busy`, `error`, `timeout`, `group not ready`; `?string $detail`: one-line diagnostic for logs |
| `QrScanned` | `string $text` |
| `QrScanCancelled` | `?string $reason` |

## Testing

The service provider adds a `FakeBridge` helper:

```php
Native::fakeBridge()->withWifiDirect(supported: true, permission: true);

Native::visit('/share')
    ->tap('Host')
    ->assertNativeCalled('WifiDirect.CreateGroup')
    ->emitNative(GroupCreated::class, ['networkName' => 'DIRECT-ab-x', 'passphrase' => 'secret123', 'band' => '5ghz']);
```

## Notes

- The host's address is always `192.168.49.1` (Android's group-owner address).
- Whether 5 GHz works depends on the chipset: some phones can't run 5 GHz Wi-Fi Direct while connected to a 2.4 GHz router. `GroupCreated::$band` tells you what you got.
- The group is not persistent: it disappears on `disconnect()` or when the app process ends.
