<?php

namespace Protendai\WifiDirect\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array status()
 * @method static bool isSupported()
 * @method static void requestPermission()
 * @method static void createGroup(string $band = '5ghz')
 * @method static void connect(string $networkName, string $passphrase, string $band = 'auto')
 * @method static void disconnect()
 * @method static bool qrCode(string $text, string $dest, int $size = 640, string $foreground = '#000000', string $background = '#FFFFFF')
 * @method static void scanQr()
 * @method static string pairingPayload(string $networkName, string $passphrase, array $extra = [])
 * @method static array|null parsePairing(string $text)
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
