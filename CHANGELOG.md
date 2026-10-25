# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-25

### Known issues
- iPhone joining an Android group is experimental: on a Galaxy A05s host (2.4 GHz) iOS refused to associate. See the README.


First public release, MIT licensed.

### Added
- JavaScript library (`resources/js/index.js`, with TypeScript types) for Inertia, Vue and React: one export per bridge function, `Events` names, `onWifiDirect()`, pairing helpers.
- `WifiDirect::isValidNetworkName()` and `WifiDirect::isValidPassphrase()`.
- `PermissionResult` now carries `reason` (`denied`, `permanently denied`, `approximate`, `interrupted`) and `permanentlyDenied`.
- `ConnectionFailed` reason `invalid`.
- FakeBridge macros `assertWifiDirectHosting()`, `assertWifiDirectJoining()`, `assertWifiDirectDisconnected()`, `assertQrScanRequested()`; `withWifiDirect()` also answers `connect()`.
- iOS: `NSLocalNetworkUsageDescription` declared by the plugin.
- Laravel Boost guidelines, Pest test suite, LICENSE, this changelog.

### Changed
- `connect()` returns `bool`: `false` (and no event) for malformed credentials or when the device refuses the call. Existing callers that ignore the return value are unaffected.
- `parsePairing()` returns `null` for codes whose network name or passphrase is malformed.
- `disconnect()` always answers with `Disconnected`, on both platforms, even when there was no link.
- `createGroup()` and `connect()` cancel anything still in flight; a new one replacing a reported link fires `Disconnected` for it first.
- Native sources moved to `resources/android/src` and `resources/ios/Sources`.
- iOS minimum lowered to 15.0 (the lowest the code needs).
- Generic Info.plist wording.

### Fixed
- Android: a malformed pairing code (network name not `DIRECT-xy…`, longer than 32 bytes, or a passphrase outside 8–63 characters) crashed the app in `WifiP2pConfig.Builder`. Credentials are now validated in PHP, JS and natively, and the Builder and P2P calls are guarded.
- Android: `disconnect()` did not cancel a queued join or BUSY retries, so the phone could join after the user cancelled.
- Android: `createGroup()` and `connect()` did not cancel each other (a hosting deadline could tear down a fresh join; a stray join timeout could fire on a host).
- Android: after a join timed out, a late `Connected` could follow `ConnectionFailed`. The join is now cancelled too, and a group that forms after cancellation is removed.
- Android 12/12L: precise location was requested without approximate, which Android ignores, so the permission was silently denied.
- Android: the activity was held strongly and events could be dispatched to a destroyed activity after it was recreated. It is now a weak reference refreshed on every call; dead activities are skipped.
- Android: P2P state was shared unsynchronised between the PHP thread and the main thread. All of it now lives on the main looper.
- Android: a hosted group or join outlived the app's activity. It is torn down when the activity is destroyed (not on configuration changes).
- Android: a permission request lost to activity recreation never answered. It now survives recreation, and every path answers with `PermissionResult`.
- Android: the first `status()` could report `enabled: false`; the sticky P2P state is read on attach.
- Android: the host now also notices members through peer-change broadcasts and a 2 s poll while hosting (helps with plain Wi-Fi clients such as iPhones).
- iOS: a dropped link was never reported. `NWPathMonitor` plus an address poll now send `Disconnected`.
- iOS: cancelling during the join prompt left the phone on the group. The SSID is recorded before the prompt and removed on cancel; a join that completes after cancellation is removed again; a join that never gets an address is left.
- iOS: QR codes ignored the foreground/background colours and were scaled with interpolation (blurry). They are now drawn module by module at an integer scale.
- Network names are no longer written to the log.

## [0.x] - 2026-10

Unreleased development versions.
