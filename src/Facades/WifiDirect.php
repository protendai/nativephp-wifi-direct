<?php

namespace Protendai\WifiDirect\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array{supported: bool, canHost: bool, enabled: bool, fiveGhz: bool, permission: bool, wifiJoined: bool, connected: bool, isGroupOwner: bool, groupOwnerIp: string} status()
 * @method static bool isSupported()
 * @method static void requestPermission()
 * @method static void createGroup(string $band = '5ghz', int $timeoutSeconds = 20)
 * @method static bool connect(string $networkName, string $passphrase, string $band = 'auto')
 * @method static void disconnect()
 * @method static bool qrCode(string $text, string $dest, int $size = 640, string $foreground = '#000000', string $background = '#FFFFFF')
 * @method static void scanQr()
 * @method static string pairingPayload(string $networkName, string $passphrase, array $extra = [])
 * @method static array{networkName: string, passphrase: string, extra: array<string, mixed>}|null parsePairing(string $text)
 * @method static bool isValidNetworkName(string $networkName)
 * @method static bool isValidPassphrase(string $passphrase)
 *
 * @see \Protendai\WifiDirect\WifiDirect
 */
class WifiDirect extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return 'protendai.wifi-direct';
    }
}
