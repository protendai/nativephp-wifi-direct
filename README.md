# Wi-Fi Direct for NativePHP Mobile

Phone-to-phone networking with **no router and no internet**. One device hosts a private Wi-Fi network (a Wi-Fi Direct group), the other joins it, and your app talks to the host over ordinary sockets or HTTP at `192.168.49.1`. Pairing is by **QR code**, and QR generation and scanning are built in.

- Often several times faster than going through a home router: each byte crosses the air once, and the group can run on **5 GHz** even when the home Wi-Fi is 2.4 GHz
- Works with **no Wi-Fi network at all**: outdoors, on a bus, at an event
- **Silent join** on Android 10+: no system "Accept invitation?" dialog
- QR code **generation** (ZXing on Android, Core Image on iOS) and **scanning** (Google code scanner on Android, camera on iOS) included

## Platform support

| Platform | Host a group | Join a group | Notes |
|---|---|---|---|
| Android 10+ (API 29) | Yes | Yes | Needs a device with Wi-Fi Direct (`status()['supported']`). |
| iOS 15+ | No | **Experimental**: an Android phone's group | iOS has no Wi-Fi Direct API. The iPhone joins the Android group as a normal Wi-Fi client (`NEHotspotConfiguration`) after a system prompt. `status()['canHost']` is `false` and `createGroup()` fails with `cannot host`. Needs the Hotspot Configuration entitlement (declared by the plugin), so a paid Apple Developer team. |

So two Android phones can pair either way round; an iPhone and an Android phone pair with the Android phone hosting.

> **iPhone joining is experimental.** In device testing (iPhone 11 on iOS 18 joining a Galaxy A05s group on 2.4 GHz, 2026-10-25) iOS accepted the join request and then showed "Unable to join the network" without ever associating with the group, so the join timed out with `ConnectionFailed('timeout')`. Android-to-Android pairing works. Until iPhone joining is confirmed on more hosts, keep a fallback for iPhones, for example both phones on the same Wi-Fi network.

## Installation

```bash
composer require protendai/nativephp-wifi-direct
php artisan native:plugin:register protendai/nativephp-wifi-direct
```

The plugin has native code: rebuild the app afterwards (`php artisan native:run`). `native:jump` is not enough.

For the JavaScript library (Inertia, Vue, React), import it from the package's `resources/js` folder, for example with a Vite alias:

```js
// vite.config.js
resolve: {
    alias: {
        '@protendai/nativephp-wifi-direct': path.resolve(__dirname, 'vendor/protendai/nativephp-wifi-direct/resources/js/index.js'),
    },
},
```

## Permissions

The plugin declares everything it needs; nothing to add by hand.

**Android**

| Permission | When |
|---|---|
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | Always (install-time, no prompt) |
| `NEARBY_WIFI_DEVICES` with `neverForLocation` | Android 13+. Runtime prompt ("Nearby devices"), **no location prompt** |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`, `maxSdkVersion="32"` | Android 10 to 12L, where Wi-Fi Direct needs precise location. Both are requested together, because Android 12 ignores a request for precise location on its own. If the user only allows approximate location, `PermissionResult` reports `reason: "approximate"`. |

The `android.hardware.wifi.direct` feature is declared as not required, so the app still installs on devices without Wi-Fi Direct.

> **Conflict with plain location permissions.** NativePHP merges plugin permissions as text. If your app or another plugin (a geolocation plugin, say) declares `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` *without* `maxSdkVersion`, the manifest ends up with two entries for the same permission, which either fails the build (`uses-permission#… duplicated`) or, after merging, caps that permission at Android 12 and breaks the other plugin on Android 13+. In that case remove the two `maxSdkVersion="32"` location lines from this plugin's `nativephp.json` in your project (or a fork): the plain declaration covers Wi-Fi Direct too.

**iOS** (`Info.plist`, generic English wording)

| Key | Why |
|---|---|
| `NSCameraUsageDescription` | `scanQr()` uses the camera |
| `NSLocalNetworkUsageDescription` | Talking to the host at `192.168.49.1` is local-network traffic, which iOS gates behind a prompt |
| Entitlement `com.apple.developer.networking.HotspotConfiguration` | Joining a network by name and passphrase |

## Quick start (PHP)

**Device A (Android) hosts** and shows its code. In an EDGE `NativeComponent`, listen with `#[On(...)]` from `Native\Mobile\Attributes\On`; in Livewire use `#[OnNative(...)]` from `Native\Mobile\Attributes\OnNative`. Handler parameters are bound from the event payload by name.

```php
use Native\Mobile\Attributes\On;
use Protendai\WifiDirect\Events\Connected;
use Protendai\WifiDirect\Events\ConnectionFailed;
use Protendai\WifiDirect\Events\GroupCreated;
use Protendai\WifiDirect\Events\PermissionResult;
use Protendai\WifiDirect\Facades\WifiDirect;

public ?string $qr = null;

public function host(): void
{
    WifiDirect::status()['permission']
        ? WifiDirect::createGroup('5ghz')
        : WifiDirect::requestPermission();
}

#[On(PermissionResult::class)]
public function onPermission(bool $granted = false, ?string $reason = null, bool $permanentlyDenied = false): void
{
    if ($granted) {
        WifiDirect::createGroup('5ghz');
    } elseif ($permanentlyDenied) {
        // Point the user at the app's settings page.
    }
}

#[On(GroupCreated::class)]
public function onGroupCreated(string $networkName = '', string $passphrase = '', string $band = ''): void
{
    $payload = WifiDirect::pairingPayload($networkName, $passphrase, ['port' => 8080]);
    $path = storage_path('app/pair.png');

    $this->qr = WifiDirect::qrCode($payload, $path) ? $path : null;
    // <native:image src="{{ $qr }}" class="w-64 h-64" />
}

#[On(Connected::class)]
public function onMemberJoined(bool $isGroupOwner = false, int $clients = 0): void
{
    // A device joined our group; it is on 192.168.49.x.
}

#[On(ConnectionFailed::class)]
public function onFailed(?string $reason = null): void
{
    // e.g. 'needs network', 'wifi off', 'permission', 'busy', 'timeout'
}
```

**Device B (Android or iPhone) scans and joins:**

```php
use Native\Mobile\Attributes\On;
use Protendai\WifiDirect\Events\Connected;
use Protendai\WifiDirect\Events\Disconnected;
use Protendai\WifiDirect\Events\QrScanned;
use Protendai\WifiDirect\Facades\WifiDirect;

public function scan(): void
{
    WifiDirect::scanQr();
}

#[On(QrScanned::class)]
public function onScanned(string $text = ''): void
{
    $pairing = WifiDirect::parsePairing($text);

    if ($pairing === null) {
        $this->error = "That isn't a pairing code.";

        return;
    }

    $this->port = (int) ($pairing['extra']['port'] ?? 8080);
    WifiDirect::connect($pairing['networkName'], $pairing['passphrase']);
}

#[On(Connected::class)]
public function onConnected(bool $isGroupOwner = false, ?string $groupOwnerIp = null): void
{
    Http::get("http://{$groupOwnerIp}:{$this->port}/hello");
}

#[On(Disconnected::class)]
public function onDisconnected(): void
{
    // The link ended (or our own disconnect() finished).
}
```

Call `WifiDirect::disconnect()` when you are done, on both devices; they return to their normal Wi-Fi.

## Usage (JavaScript)

```js
import { scanQr, parsePairing, connect, disconnect, onWifiDirect, Events } from '@protendai/nativephp-wifi-direct';

const stopScanned = onWifiDirect(Events.QrScanned, async ({ text }) => {
    const pairing = parsePairing(text);
    if (pairing) {
        await connect(pairing.networkName, pairing.passphrase);
    }
});

onWifiDirect(Events.Connected, ({ groupOwnerIp }) => {
    fetch(`http://${groupOwnerIp}:8080/hello`);
});

onWifiDirect(Events.ConnectionFailed, ({ reason }) => console.warn('Wi-Fi Direct failed:', reason));

await scanQr();
// later: stopScanned(); await disconnect();
```

Every bridge function has a named export (`status`, `requestPermission`, `createGroup`, `connect`, `disconnect`, `qrCode`, `scanQr`), plus `pairingPayload`, `parsePairing`, `isValidNetworkName`, `isValidPassphrase`, `onWifiDirect(event, callback)` (returns an unsubscribe function), `Events` and `OWNER_IP`. `On(Events.Connected, ...)` from `@nativephp/mobile` works too. Types are in `resources/js/index.d.ts`.

## API

Facade `Protendai\WifiDirect\Facades\WifiDirect` (class `Protendai\WifiDirect\WifiDirect`). Every call returns straight away; slow work answers with an event.

| Method | Returns | Notes |
|---|---|---|
| `status()` | `array` | `supported`, `canHost`, `enabled` (Wi-Fi Direct radio on), `fiveGhz`, `permission`, `wifiJoined` (joined to some Wi-Fi network), `connected`, `isGroupOwner`, `groupOwnerIp`. Every key is always present. |
| `isSupported()` | `bool` | Device has Wi-Fi Direct (Android 10+) or can join (iOS). |
| `requestPermission()` | `void` | → `PermissionResult` (immediately if already granted; always `granted: true` on iOS). |
| `createGroup(string $band = '5ghz', int $timeoutSeconds = 20)` | `void` | Android. `'5ghz'`, `'2.4ghz'` or `'auto'`; falls back to any band, the Wi-Fi network's channel, fixed channels 6/11 and the legacy API if the chipset refuses. Cancels anything in flight first. → `GroupCreated` or `ConnectionFailed`, always within the timeout (5 to 300 s). |
| `connect(string $networkName, string $passphrase, string $band = 'auto')` | `bool` | Silent join. Cancels anything in flight first. → `Connected` or `ConnectionFailed` (30 s at most on Android, 20 s after the prompt on iOS). Returns `false` and fires **no event** when the credentials are malformed or the device refuses the call. |
| `disconnect()` | `void` | Leaves or tears down the group and cancels anything in flight (a pending join, `createGroup()` still trying, the iOS join prompt). → `Disconnected`, **always**, even with no link. |
| `qrCode(string $text, string $dest, int $size = 640, string $foreground = '#000000', string $background = '#FFFFFF')` | `bool` | Writes a `$size` x `$size` PNG (64 to 4096 px) with sharp modules. Colours are `#RRGGBB` or `#AARRGGBB`. |
| `scanQr()` | `void` | Android: Google code scanner (Play services, no camera permission, the module downloads on first use). iOS: full-screen camera. → `QrScanned` or `QrScanCancelled`. |
| `pairingPayload(string $networkName, string $passphrase, array $extra = [])` | `string` | Compact JSON for the QR code (`{"wd":1,"n":…,"p":…}` plus `$extra`). Put your own connection data (port, display name) in `$extra`. |
| `parsePairing(string $text)` | `?array` | `['networkName', 'passphrase', 'extra']`, or `null` for foreign codes and for codes with a malformed network name or passphrase. |
| `isValidNetworkName(string $name)` (static) | `bool` | `DIRECT-` plus two letters or digits, at most 32 bytes of UTF-8 (Android's rule). |
| `isValidPassphrase(string $passphrase)` (static) | `bool` | 8 to 63 printable ASCII characters (WPA2). |

`WifiDirect::OWNER_IP` is `'192.168.49.1'`, the host's address on every group.

### Cancellation

`createGroup()`, `connect()` and `disconnect()` each cancel whatever the previous call still had running (timeouts, retries, polls). Nothing from a cancelled call fires afterwards: no late `GroupCreated`, no `Connected` after a `ConnectionFailed`. If a new `createGroup()` or `connect()` replaces a link that had been reported with `Connected`, `Disconnected` fires for it first.

## Events

All under `Protendai\WifiDirect\Events`. Declare handler parameters with defaults; extra payload keys are ignored.

| Event | Payload | When |
|---|---|---|
| `PermissionResult` | `bool $granted`, `?string $reason`, `bool $permanentlyDenied` | Answer to `requestPermission()`. When denied, `$reason` is `denied`, `permanently denied` (best guess: Android won't prompt again, send the user to settings), `approximate` (Android 12: only approximate location allowed) or `interrupted`. |
| `GroupCreated` | `string $networkName`, `string $passphrase`, `string $ownerIp`, `int $frequency`, `string $band` (`5ghz`/`2.4ghz`) | The group is up (Android). Share the name and passphrase, e.g. with `qrCode(pairingPayload(...))`. |
| `Connected` | `bool $isGroupOwner`, `?string $groupOwnerIp`, `int $clients`, `int $frequency`, `bool $connected` | Joiner: we joined. Host: a member joined (again whenever the member count changes). |
| `Disconnected` | none | `disconnect()` finished (always), the joined group went away, the last member left a group we host, or a group we host went away. |
| `ConnectionFailed` | `?string $reason`, `?string $detail` | See below. `$detail` is a one-line diagnostic for logs (raw reason, radio and location state, OS version, device model); it never contains the network name or passphrase. |
| `QrScanned` | `string $text` | The scanner read a code. |
| `QrScanCancelled` | `?string $reason` | The scanner closed without a code: `cancelled`, `camera denied` (iOS), `no camera`, or the scanner's error message. |

### ConnectionFailed reasons

| Reason | Meaning |
|---|---|
| `permission` | Permission missing or revoked. Call `requestPermission()`. |
| `unsupported` | No Wi-Fi Direct on this device. |
| `wifi off` | Wi-Fi is switched off (no network needs to be joined, but the radio must be on). |
| `location off` | Android 12 and older (and some vendor builds) refuse Wi-Fi Direct with Location off. |
| `busy` | The radio is busy, typically with Quick Share, Smart View or screen mirroring. Retried 3 times before failing. |
| `needs network` | This phone can only host while Wi-Fi is joined to a network (some Samsung chips). Let the other phone host instead. |
| `group not ready` | The chipset accepted the request but no group formed within the timeout. |
| `timeout` | Joining took longer than the timeout. The join is cancelled too. |
| `invalid` | The network name or passphrase was refused by the platform. |
| `error` | Any other platform error (see `$detail`). |
| `cannot host` | iOS: iPhones can only join. Let the Android phone host. |
| `declined` | iOS: the user declined the join prompt. |

## Testing

The service provider adds FakeBridge macros for app tests:

```php
use Native\Mobile\Testing\Native;
use Protendai\WifiDirect\Events\GroupCreated;

Native::fakeBridge()->withWifiDirect(supported: true, permission: true, canHost: true);

Native::test(Share::class)
    ->tap('Host')
    ->assertWifiDirectHosting(fn (array $p) => $p['band'] === '5ghz')
    ->emitNative(GroupCreated::class, ['networkName' => 'DIRECT-ab-x7Kp2q', 'passphrase' => 'secret123', 'band' => '5ghz']);
```

`withWifiDirect(supported, permission, fiveGhz, wifiJoined, canHost)` answers `status()` and makes `qrCode()` and `connect()` succeed. Assertions: `assertWifiDirectHosting(?callable)`, `assertWifiDirectJoining(?callable)`, `assertWifiDirectDisconnected()`, `assertQrScanRequested()`.

The plugin's own tests: `composer install && composer test`.

## Limitations and notes

- **The host's address is always `192.168.49.1`**; members get other addresses on `192.168.49.0/24`. To reach a member from the host, have the member call the host first (it learns the member's address from the request) or sweep the subnet.
- **5 GHz depends on the chipset**: some phones can't run a 5 GHz group while joined to a 2.4 GHz network. `GroupCreated::$band` says what you got.
- **Groups are not persistent**: the group ends on `disconnect()` or when the app's activity is destroyed (Android tears it down then; a configuration change doesn't count). Navigating between screens keeps it; `status()` tells a new screen whether a link is up.
- **iPhone joining an Android host (to verify per device)**: the Android host learns about a member from the system's group client list. A plain Wi-Fi client (the iPhone) does not show up there on every Android build, so the host may get no `Connected` when an iPhone joins. The plugin also polls the group every 2 s while hosting, but design the protocol so the joiner announces itself to the host over the link (e.g. an HTTP request to `192.168.49.1`) rather than relying on the host's `Connected` alone.
- **iOS link loss** is detected by watching the Wi-Fi path and polling for the `192.168.49.x` address; `Disconnected` follows within about 3 seconds.
- **Logs**: the plugin never logs network names or passphrases, but NativePHP core logs every event payload it dispatches (logcat tag `JSFUNC` on Android), and `GroupCreated` carries the passphrase. Release builds are not affected unless you ship verbose logging; treat debug logs as sensitive.

## Support

Questions and bugs: [GitHub issues](https://github.com/protendai/nativephp-wifi-direct/issues).

## License

MIT. See [LICENSE](LICENSE).
