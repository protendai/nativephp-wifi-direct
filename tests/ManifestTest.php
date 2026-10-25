<?php

/**
 * nativephp.json against composer.json, the PHP facade, the native sources and the JS library.
 */
beforeEach(function () {
    $this->manifest = manifest();
    $this->kotlin = native_sources('kt');
    $this->swift = native_sources('swift');
    $this->php = file_get_contents(plugin_path('src/WifiDirect.php'));
    $this->js = file_get_contents(plugin_path('resources/js/index.js'));
});

const BRIDGE_FUNCTIONS = ['Status', 'RequestPermission', 'CreateGroup', 'Connect', 'Disconnect', 'QrCode', 'ScanQr'];

describe('metadata', function () {
    it('has the marketplace fields', function () {
        expect($this->manifest)->toHaveKeys(['name', 'version', 'description', 'namespace', 'keywords', 'category', 'license', 'author', 'homepage', 'repository', 'platforms', 'bridge_functions', 'events', 'android', 'ios'])
            ->and($this->manifest['name'])->toBe('protendai/nativephp-wifi-direct')
            ->and($this->manifest['version'])->toBe('1.0.0')
            ->and($this->manifest['namespace'])->toBe('WifiDirect')
            ->and($this->manifest['license'])->toBe('MIT')
            ->and($this->manifest['platforms'])->toBe(['android', 'ios'])
            ->and($this->manifest['author']['name'])->toBe('Tendai Karuma')
            ->and($this->manifest['repository'])->toBe('https://github.com/protendai/nativephp-wifi-direct');
    });

    it('agrees with composer.json', function () {
        $composer = json_decode(file_get_contents(plugin_path('composer.json')), true);

        expect($composer['name'])->toBe($this->manifest['name'])
            ->and($composer['license'])->toBe('MIT')
            ->and($composer['type'])->toBe('nativephp-plugin')
            ->and($composer['keywords'])->toContain('nativephp-plugin')
            ->and($composer['require']['nativephp/mobile'])->toBe('^4.0')
            ->and($composer['extra']['nativephp']['manifest'])->toBe('nativephp.json')
            ->and($composer['extra']['laravel']['providers'])->toBe(['Protendai\\WifiDirect\\WifiDirectServiceProvider'])
            ->and(file_exists(plugin_path('LICENSE')))->toBeTrue()
            ->and(file_get_contents(plugin_path('CHANGELOG.md')))->toContain('## [1.0.0] - 2026-10-25');
    });

    it('needs Android 10 (WifiP2pConfig.Builder) and iOS 15 (UIButton.Configuration)', function () {
        expect($this->manifest['android']['min_version'])->toBe(29)
            ->and($this->manifest['ios']['min_version'])->toBe('15.0')
            ->and($this->kotlin)->toContain('WifiP2pConfig.Builder()')
            ->and($this->swift)->toContain('UIButton.Configuration');
    });

    it('declares the permissions the native code checks', function () {
        $permissions = $this->manifest['android']['permissions'];

        expect($permissions)->toContain('<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />')
            ->toContain('<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />')
            // Android 12/12L ignore a FINE request without COARSE.
            ->toContain('<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="32" />')
            ->and($this->kotlin)->toContain('arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)')
            ->toContain('arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)');
    });

    it('declares generic iOS usage strings and the hotspot entitlement', function () {
        $plist = $this->manifest['ios']['info_plist'];

        expect($plist)->toHaveKeys(['NSCameraUsageDescription', 'NSLocalNetworkUsageDescription'])
            ->and($this->manifest['ios']['entitlements'])->toBe(['com.apple.developer.networking.HotspotConfiguration' => true]);

        foreach ($plist as $text) {
            expect($text)->not->toMatch('/tumira|ftree/i');
        }
    });
});

describe('bridge functions', function () {
    it('declares the seven functions on both platforms, with descriptions', function () {
        expect(array_map(fn ($f) => [$f['name'], $f['android'], $f['ios']], $this->manifest['bridge_functions']))
            ->toBe(array_map(fn ($name) => [
                "WifiDirect.$name",
                "com.protendai.wifidirect.WifiDirectFunctions.$name",
                "WifiDirectFunctions.$name",
            ], BRIDGE_FUNCTIONS));

        foreach ($this->manifest['bridge_functions'] as $function) {
            expect($function['description'] ?? '')->not->toBeEmpty();
        }
    });

    it('implements every Android class in the manifest package', function () {
        foreach (glob(plugin_path('resources/android/src/*.kt')) as $file) {
            expect(file_get_contents($file))->toStartWith('package com.protendai.wifidirect');
        }

        expect($this->kotlin)->toContain('object WifiDirectFunctions');

        foreach ($this->manifest['bridge_functions'] as $function) {
            $class = substr(strrchr($function['android'], '.'), 1);
            expect($this->kotlin)->toContain("class $class(private val activity: FragmentActivity) : BridgeFunction");
        }
    });

    it('implements every iOS class', function () {
        expect($this->swift)->toContain('enum WifiDirectFunctions');

        foreach ($this->manifest['bridge_functions'] as $function) {
            $class = substr(strrchr($function['ios'], '.'), 1);
            expect($this->swift)->toContain("class $class: BridgeFunction");
        }
    });

    it('calls every function from PHP and JS, and nothing undeclared', function () {
        $declared = array_column($this->manifest['bridge_functions'], 'name');

        preg_match_all("/'(WifiDirect\.[A-Za-z]+)'/", $this->php, $php);
        preg_match_all("/'(WifiDirect\.[A-Za-z]+)'/", $this->js, $js);

        expect(array_values(array_unique($php[1])))->toEqualCanonicalizing($declared)
            ->and(array_values(array_unique($js[1])))->toEqualCanonicalizing($declared);
    });
});

describe('native behaviour', function () {
    it('validates credentials natively before WifiP2pConfig.Builder and NEHotspotConfiguration', function () {
        expect($this->kotlin)->toContain('fun credentialsError(networkName: String, passphrase: String): String?')
            ->toContain('Regex("^DIRECT-[a-zA-Z0-9]{2}.*")')
            ->toContain('WifiDirectManager.credentialsError(name, passphrase)')
            ->toContain('fail("invalid"')
            ->and($this->swift)->toContain('static func credentialsError(networkName: String, passphrase: String) -> String?')
            ->toContain('WifiDirectJoiner.credentialsError(networkName: name, passphrase: passphrase)');
    });

    it('cancels in-flight work from every entry point', function () {
        expect($this->kotlin)->toContain('main.removeCallbacksAndMessages(OP)')
            ->toContain('generation++')
            ->and(substr_count($this->kotlin, 'beginOperation()'))->toBeGreaterThanOrEqual(3);
    });

    it('keeps a weak activity reference and confines P2P work to the main looper', function () {
        expect($this->kotlin)->toContain('WeakReference<FragmentActivity>')
            ->toContain('main.post { startHosting(band, timeoutMs) }')
            ->toContain('main.post { startJoining(networkName, passphrase, band) }')
            ->toContain('main.post { leave() }')
            ->toContain('isChangingConfigurations')
            ->not->toMatch('/private var activityRef: FragmentActivity/');
    });

    it('detects link loss and cancelled joins on iOS', function () {
        expect($this->swift)->toContain('NWPathMonitor(requiredInterfaceType: .wifi)')
            ->toContain('removeConfiguration(forSSID: networkName)');
    });

    it('never logs network names or passphrases', function () {
        preg_match_all('/Log\.[dwie]\([^\n]*|NSLog\([^\n]*|print\([^\n]*/', $this->kotlin."\n".$this->swift, $logs);

        expect($logs[0])->not->toBeEmpty();
        foreach ($logs[0] as $line) {
            expect($line)->not->toMatch('/networkName|passphrase|ssid|groupName|\$name/i');
        }
    });

    it('carries no app-specific wording', function () {
        $everything = $this->kotlin.$this->swift.$this->php.$this->js
            .file_get_contents(plugin_path('README.md'))
            .file_get_contents(plugin_path('nativephp.json'));

        expect($everything)->not->toMatch('/tumira|ftree/i');
    });
});

describe('documentation', function () {
    it('documents every public method and event in the README and Boost guidelines', function () {
        $readme = file_get_contents(plugin_path('README.md'));
        $boost = file_get_contents(plugin_path('resources/boost/guidelines/core.blade.php'));
        $methods = array_map(
            fn (ReflectionMethod $m) => $m->getName(),
            (new ReflectionClass(\Protendai\WifiDirect\WifiDirect::class))->getMethods(ReflectionMethod::IS_PUBLIC),
        );

        foreach ($methods as $method) {
            expect($readme)->toContain("$method(")
                ->and($boost)->toContain("$method(");
        }

        foreach (manifest()['events'] as $event) {
            expect($readme)->toContain('`'.class_basename($event).'`')
                ->and($boost)->toContain(class_basename($event));
        }
    });

    it('documents every method in the facade docblock', function () {
        $facade = file_get_contents(plugin_path('src/Facades/WifiDirect.php'));

        foreach ((new ReflectionClass(\Protendai\WifiDirect\WifiDirect::class))->getMethods(ReflectionMethod::IS_PUBLIC) as $method) {
            expect($facade)->toContain(' '.$method->getName().'(');
        }
    });
});
