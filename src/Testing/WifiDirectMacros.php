<?php

namespace Protendai\WifiDirect\Testing;

use Native\Mobile\Testing\FakeBridge;

/**
 * Wi-Fi Direct test vocabulary, registered as FakeBridge macros:
 *
 *     Native::fakeBridge()->withWifiDirect(supported: true, permission: true);
 *
 *     Native::test(Share::class)
 *         ->tap('Host')
 *         ->assertWifiDirectHosting()
 *         ->emitNative(GroupCreated::class, ['networkName' => 'DIRECT-ab-x', 'passphrase' => 'secret123', 'band' => '5ghz']);
 */
class WifiDirectMacros
{
    public static function register(): void
    {
        if (FakeBridge::hasMacro('withWifiDirect')) {
            return;
        }

        /** Answer WifiDirect.Status (and make QrCode and Connect succeed) like a device would. */
        FakeBridge::macro('withWifiDirect', function (bool $supported = true, bool $permission = true, bool $fiveGhz = true, bool $wifiJoined = true, bool $canHost = true): FakeBridge {
            /** @var FakeBridge $this */
            return $this
                ->respondTo('WifiDirect.Status', [
                    'supported' => $supported,
                    'canHost' => $supported && $canHost,
                    'enabled' => $supported,
                    'fiveGhz' => $fiveGhz,
                    'permission' => $permission,
                    'wifiJoined' => $wifiJoined,
                    'connected' => false,
                    'isGroupOwner' => false,
                    'groupOwnerIp' => '',
                ])
                ->respondTo('WifiDirect.QrCode', ['ok' => true])
                ->respondTo('WifiDirect.Connect', ['ok' => true]);
        });

        /** Assert createGroup() was called, optionally matching its params (band, timeoutSeconds). */
        FakeBridge::macro('assertWifiDirectHosting', function (?callable $filter = null): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('WifiDirect.CreateGroup', $filter);
        });

        /** Assert connect() reached the device, optionally matching its params (networkName, passphrase, band). */
        FakeBridge::macro('assertWifiDirectJoining', function (?callable $filter = null): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('WifiDirect.Connect', $filter);
        });

        FakeBridge::macro('assertWifiDirectDisconnected', function (): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('WifiDirect.Disconnect');
        });

        FakeBridge::macro('assertQrScanRequested', function (): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('WifiDirect.ScanQr');
        });
    }
}
