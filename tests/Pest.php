<?php

use Native\Mobile\Testing\FakeBridge;
use Protendai\WifiDirect\Tests\Bridge;
use Protendai\WifiDirect\Testing\WifiDirectMacros;

/*
 * Plain Pest tests, no Laravel app: the facade's bridge calls go to a
 * FakeBridge held by Tests\Bridge, through this stand-in for the native
 * nativephp_call() function.
 */
if (! function_exists('nativephp_call')) {
    function nativephp_call(string $method, string $params = '{}'): ?string
    {
        return Bridge::$fake?->call($method, $params);
    }
}

if (! trait_exists(\Illuminate\Foundation\Events\Dispatchable::class)) {
    require __DIR__.'/Stubs/Dispatchable.php';
}

WifiDirectMacros::register();

uses()->beforeEach(function () {
    Bridge::$fake = new FakeBridge;
})->afterEach(function () {
    Bridge::$fake = null;
})->in(__DIR__);

function plugin_path(string $path = ''): string
{
    return dirname(__DIR__).($path === '' ? '' : '/'.$path);
}

function manifest(): array
{
    return json_decode(file_get_contents(plugin_path('nativephp.json')), true, flags: JSON_THROW_ON_ERROR);
}

/** All Kotlin (or Swift) sources of the plugin, concatenated. */
function native_sources(string $extension): string
{
    $dir = plugin_path($extension === 'kt' ? 'resources/android/src' : 'resources/ios/Sources');
    $files = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS));
    $source = '';

    foreach ($files as $file) {
        if ($file->getExtension() === $extension) {
            $source .= file_get_contents($file->getPathname())."\n";
        }
    }

    return $source;
}
