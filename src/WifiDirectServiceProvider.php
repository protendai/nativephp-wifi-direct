<?php

namespace Protendai\WifiDirect;

use Illuminate\Support\ServiceProvider;
use Native\Mobile\Testing\FakeBridge;
use Protendai\WifiDirect\Testing\WifiDirectMacros;

class WifiDirectServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton('protendai.wifi-direct', fn (): WifiDirect => new WifiDirect);
        $this->app->alias('protendai.wifi-direct', WifiDirect::class);
    }

    public function boot(): void
    {
        // Test sugar (withWifiDirect(), assertWifiDirectHosting(), ...) on a core with a macroable FakeBridge.
        if (class_exists(FakeBridge::class) && method_exists(FakeBridge::class, 'macro')) {
            WifiDirectMacros::register();
        }
    }
}
