<?php

use Protendai\WifiDirect\Events\Connected;
use Protendai\WifiDirect\Events\ConnectionFailed;
use Protendai\WifiDirect\Events\Disconnected;
use Protendai\WifiDirect\Events\GroupCreated;
use Protendai\WifiDirect\Events\PermissionResult;
use Protendai\WifiDirect\Events\QrScanCancelled;
use Protendai\WifiDirect\Events\QrScanned;

/**
 * The payloads the native side dispatches. NativePHP builds the event with
 * these as named arguments, so every key must be a constructor parameter.
 */
dataset('payloads', [
    'PermissionResult granted' => [PermissionResult::class, ['granted' => true, 'permanentlyDenied' => false]],
    'PermissionResult denied' => [PermissionResult::class, ['granted' => false, 'permanentlyDenied' => true, 'reason' => 'permanently denied']],
    'GroupCreated' => [GroupCreated::class, ['networkName' => 'DIRECT-ab-x7Kp2q', 'passphrase' => 'secret123', 'ownerIp' => '192.168.49.1', 'frequency' => 5180, 'band' => '5ghz']],
    'Connected' => [Connected::class, ['connected' => true, 'isGroupOwner' => false, 'groupOwnerIp' => '192.168.49.1', 'clients' => 0, 'frequency' => 0]],
    'Disconnected' => [Disconnected::class, []],
    'ConnectionFailed' => [ConnectionFailed::class, ['reason' => 'timeout', 'detail' => 'raw=timeout p2p=on wifi=on location=on sdk=34 Google Pixel 8']],
    'QrScanned' => [QrScanned::class, ['text' => '{"wd":1}']],
    'QrScanCancelled' => [QrScanCancelled::class, ['reason' => 'cancelled']],
]);

it('builds every event from its native payload', function (string $class, array $payload) {
    $event = new $class(...$payload);

    expect($event)->toBeInstanceOf($class);
    foreach ($payload as $key => $value) {
        expect($event->{$key})->toBe($value);
    }
})->with('payloads');

it('builds every event with no payload at all', function (string $class) {
    expect(new $class)->toBeInstanceOf($class);
})->with(fn () => array_map(fn ($c) => [$c], manifest()['events']));

it('lists exactly these events in the manifest, the native code and the JS library', function () {
    $events = manifest()['events'];
    $kotlin = native_sources('kt');
    $swift = native_sources('swift');
    $js = file_get_contents(plugin_path('resources/js/index.js'));

    expect($events)->toBe([
        PermissionResult::class, GroupCreated::class, Connected::class, Disconnected::class,
        ConnectionFailed::class, QrScanned::class, QrScanCancelled::class,
    ])
        ->and($kotlin)->toContain('"Protendai\\\\WifiDirect\\\\Events\\\\"')
        ->and($swift)->toContain('"Protendai\\\\WifiDirect\\\\Events\\\\\\(event)"');

    foreach ($events as $event) {
        $short = class_basename($event);
        expect($kotlin)->toContain("\"$short\"")
            ->and($js)->toContain("$short: '".str_replace('\\', '\\\\', $event)."'");

        // iPhones can't host, so iOS never sends GroupCreated.
        if ($short !== 'GroupCreated') {
            expect($swift)->toContain("\"$short\"");
        }
    }
});

it('sends only payload keys the events accept', function () {
    $kotlin = native_sources('kt');
    $swift = native_sources('swift');

    // Every .put("key", ...) on Android and "key": on iOS must be a constructor parameter of some event.
    $params = [];
    foreach (manifest()['events'] as $event) {
        foreach ((new ReflectionClass($event))->getConstructor()?->getParameters() ?? [] as $parameter) {
            $params[] = $parameter->getName();
        }
    }

    preg_match_all('/\.put\("([a-zA-Z]+)"/', $kotlin, $put);
    preg_match_all('/dispatch\("[A-Za-z]+", \[(.*?)\]\)/s', $swift, $dispatches);
    preg_match_all('/"([a-zA-Z]+)":/', implode("\n", $dispatches[1]), $swiftKeys);

    expect(array_diff(array_unique($put[1]), $params))->toBe([])
        ->and(array_diff(array_unique($swiftKeys[1]), $params))->toBe([]);
});
