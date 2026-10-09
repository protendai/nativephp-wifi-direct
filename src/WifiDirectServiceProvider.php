<?php

namespace Protendai\WifiDirect;

use Illuminate\Support\ServiceProvider;
use Native\Mobile\Testing\FakeBridge;

class WifiDirectServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton('protendai.wifi-direct', fn (): WifiDirect => new WifiDirect);
    }

    public function boot(): void
    {
        $this->registerFakeBridgeMacros();
    }

    /**
     * Test helper for screens that use the plugin:
     *
     *   Native::fakeBridge()->withWifiDirect(supported: true, permission: true);
     */
    private function registerFakeBridgeMacros(): void
    {
        if (! class_exists(FakeBridge::class) || FakeBridge::hasMacro('withWifiDirect')) {
            return;
        }

        FakeBridge::macro('withWifiDirect', function (bool $supported = true, bool $permission = true, bool $fiveGhz = true): FakeBridge {
            /** @var FakeBridge $this */
            return $this
                ->respondTo('WifiDirect.Status', [
                    'supported' => $supported,
                    'enabled' => $supported,
                    'fiveGhz' => $fiveGhz,
                    'permission' => $permission,
                    'connected' => false,
                    'isGroupOwner' => false,
                    'groupOwnerIp' => '',
                ])
                ->respondTo('WifiDirect.QrCode', ['ok' => true]);
        });
    }
}
