<?php

use Protendai\WifiDirect\Tests\Bridge;
use Protendai\WifiDirect\WifiDirect;

it('fakes a device with withWifiDirect()', function () {
    Bridge::$fake->withWifiDirect(supported: true, permission: false, fiveGhz: false, wifiJoined: false, canHost: false);

    $wifi = new WifiDirect;

    expect($wifi->status())->toMatchArray([
        'supported' => true,
        'canHost' => false,
        'permission' => false,
        'fiveGhz' => false,
        'wifiJoined' => false,
        'connected' => false,
    ])
        ->and($wifi->qrCode('x', '/tmp/x.png'))->toBeTrue()
        ->and($wifi->connect('DIRECT-ab-x', 'secret123'))->toBeTrue();
});

it('reports an unsupported device as unable to host', function () {
    Bridge::$fake->withWifiDirect(supported: false);

    expect((new WifiDirect)->status())->toMatchArray(['supported' => false, 'canHost' => false, 'enabled' => false]);
});

it('asserts hosting, joining, disconnecting and scanning', function () {
    $wifi = new WifiDirect;
    $wifi->createGroup('5ghz', 25);
    $wifi->connect('DIRECT-ab-x', 'secret123', '2.4ghz');
    $wifi->scanQr();
    $wifi->disconnect();

    Bridge::$fake
        ->assertWifiDirectHosting(fn (array $p) => $p['timeoutSeconds'] === 25)
        ->assertWifiDirectJoining(fn (array $p) => $p['band'] === '2.4ghz')
        ->assertQrScanRequested()
        ->assertWifiDirectDisconnected();
});
