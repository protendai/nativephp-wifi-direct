<?php

use Illuminate\Support\Facades\Facade;
use Protendai\WifiDirect\Facades\WifiDirect as WifiDirectFacade;
use Protendai\WifiDirect\Tests\Bridge;
use Protendai\WifiDirect\WifiDirect;

beforeEach(function () {
    $this->wifi = new WifiDirect;
});

describe('status()', function () {
    it('fills every key with a safe default when the device says nothing', function () {
        expect($this->wifi->status())->toBe([
            'supported' => false,
            'canHost' => true,
            'enabled' => false,
            'fiveGhz' => false,
            'permission' => false,
            'wifiJoined' => true,
            'connected' => false,
            'isGroupOwner' => false,
            'groupOwnerIp' => '',
        ]);
    });

    it('takes the device answer and drops unknown keys', function () {
        Bridge::$fake->respondTo('WifiDirect.Status', [
            'supported' => true,
            'canHost' => false,
            'connected' => true,
            'groupOwnerIp' => '192.168.49.1',
            'somethingElse' => 1,
        ]);

        $status = $this->wifi->status();

        expect($status)->toHaveKeys(['supported', 'canHost', 'enabled', 'fiveGhz', 'permission', 'wifiJoined', 'connected', 'isGroupOwner', 'groupOwnerIp'])
            ->not->toHaveKey('somethingElse')
            ->and($status['supported'])->toBeTrue()
            ->and($status['canHost'])->toBeFalse()
            ->and($status['connected'])->toBeTrue()
            ->and($status['groupOwnerIp'])->toBe('192.168.49.1')
            ->and($this->wifi->isSupported())->toBeTrue();
    });

    it('falls back to defaults on a bridge error envelope', function () {
        Bridge::$fake->respondTo('WifiDirect.Status', ['status' => 'error', 'code' => 'UNKNOWN_ERROR', 'message' => 'boom']);

        expect($this->wifi->status()['supported'])->toBeFalse();
    });
});

it('asks for permission', function () {
    $this->wifi->requestPermission();

    Bridge::$fake->assertCalled('WifiDirect.RequestPermission');
});

it('passes band and timeout to createGroup', function () {
    $this->wifi->createGroup();
    $this->wifi->createGroup('2.4ghz', 45);

    expect(array_column(Bridge::$fake->callsTo('WifiDirect.CreateGroup'), 'params'))->toBe([
        ['band' => '5ghz', 'timeoutSeconds' => 20],
        ['band' => '2.4ghz', 'timeoutSeconds' => 45],
    ]);
});

describe('connect()', function () {
    it('sends valid credentials and reports the device accepting them', function () {
        Bridge::$fake->respondTo('WifiDirect.Connect', ['ok' => true]);

        expect($this->wifi->connect('DIRECT-ab-x7Kp2q', 'secret123'))->toBeTrue();

        Bridge::$fake->assertCalled('WifiDirect.Connect', fn (array $p) => $p === [
            'networkName' => 'DIRECT-ab-x7Kp2q',
            'passphrase' => 'secret123',
            'band' => 'auto',
        ]);
    });

    it('returns false when the device refuses', function () {
        Bridge::$fake->respondTo('WifiDirect.Connect', ['ok' => false, 'error' => 'network name must start with DIRECT-xy']);

        expect($this->wifi->connect('DIRECT-ab-x', 'secret123', '5ghz'))->toBeFalse();
    });

    it('never sends malformed credentials to the device', function (string $name, string $passphrase) {
        expect($this->wifi->connect($name, $passphrase))->toBeFalse();

        Bridge::$fake->assertNotCalled('WifiDirect.Connect');
    })->with([
        'no DIRECT- prefix' => ['MyHomeWifi', 'secret123'],
        'lowercase prefix' => ['direct-ab-x', 'secret123'],
        'one character after the prefix' => ['DIRECT-a', 'secret123'],
        'name over 32 bytes' => ['DIRECT-ab-'.str_repeat('x', 23), 'secret123'],
        'multibyte name over 32 bytes' => ['DIRECT-ab-'.str_repeat('é', 12), 'secret123'],
        'newline in name' => ["DIRECT-ab-x\ny", 'secret123'],
        'short passphrase' => ['DIRECT-ab-x', 'short'],
        'long passphrase' => ['DIRECT-ab-x', str_repeat('p', 64)],
        'non-ASCII passphrase' => ['DIRECT-ab-x', 'pässwörd123'],
        'control character in passphrase' => ['DIRECT-ab-x', "secret\t123"],
    ]);
});

it('disconnects and scans', function () {
    $this->wifi->disconnect();
    $this->wifi->scanQr();

    Bridge::$fake->assertCallOrder(['WifiDirect.Disconnect', 'WifiDirect.ScanQr']);
});

describe('qrCode()', function () {
    it('passes text, destination, size and colours and decodes ok', function () {
        Bridge::$fake->respondTo('WifiDirect.QrCode', ['ok' => true]);

        expect($this->wifi->qrCode('hello', '/tmp/qr.png', 512, '#1B2027', '#FFFFFF'))->toBeTrue();

        Bridge::$fake->assertCalled('WifiDirect.QrCode', fn (array $p) => $p === [
            'text' => 'hello',
            'dest' => '/tmp/qr.png',
            'size' => 512,
            'foreground' => '#1B2027',
            'background' => '#FFFFFF',
        ]);
    });

    it('is false when the device could not write it', function () {
        Bridge::$fake->respondTo('WifiDirect.QrCode', ['ok' => false]);

        expect($this->wifi->qrCode('hello', '/nope/qr.png'))->toBeFalse();
    });
});

describe('pairing payloads', function () {
    it('round-trips name, passphrase and extra data', function () {
        $payload = $this->wifi->pairingPayload('DIRECT-ab-x7Kp2q', 'secret123', ['port' => 8080, 'a' => 'Phone']);

        expect(json_decode($payload, true))->toBe(['wd' => 1, 'n' => 'DIRECT-ab-x7Kp2q', 'p' => 'secret123', 'port' => 8080, 'a' => 'Phone'])
            ->and($this->wifi->parsePairing($payload))->toBe([
                'networkName' => 'DIRECT-ab-x7Kp2q',
                'passphrase' => 'secret123',
                'extra' => ['port' => 8080, 'a' => 'Phone'],
            ]);
    });

    it('rejects foreign and malformed codes', function (string $text) {
        expect($this->wifi->parsePairing($text))->toBeNull();
    })->with([
        'not JSON' => ['https://example.com'],
        'JSON list' => ['[1,2,3]'],
        'other JSON' => ['{"hello":"world"}'],
        'wrong version' => ['{"wd":2,"n":"DIRECT-ab-x","p":"secret123"}'],
        'name not a string' => ['{"wd":1,"n":12,"p":"secret123"}'],
        'bad network name' => ['{"wd":1,"n":"HomeWifi","p":"secret123"}'],
        'name too long' => ['{"wd":1,"n":"DIRECT-ab-'.str_repeat('x', 30).'","p":"secret123"}'],
        'passphrase too short' => ['{"wd":1,"n":"DIRECT-ab-x","p":"1234567"}'],
        'passphrase too long' => ['{"wd":1,"n":"DIRECT-ab-x","p":"'.str_repeat('p', 64).'"}'],
    ]);
});

describe('credential rules', function () {
    it('accepts what Android generates', function () {
        expect(WifiDirect::isValidNetworkName('DIRECT-ab-x7Kp2q'))->toBeTrue()
            ->and(WifiDirect::isValidNetworkName('DIRECT-Xy'))->toBeTrue()
            ->and(WifiDirect::isValidNetworkName('DIRECT-12-Galaxy A05s'))->toBeTrue()
            ->and(WifiDirect::isValidNetworkName('DIRECT-ab-'.str_repeat('x', 22)))->toBeTrue()
            ->and(WifiDirect::isValidPassphrase('abcdefgh'))->toBeTrue()
            ->and(WifiDirect::isValidPassphrase(str_repeat('p', 63)))->toBeTrue()
            ->and(WifiDirect::isValidPassphrase('with spaces & symbols!'))->toBeTrue();
    });

    it('rejects names Android would throw on', function () {
        expect(WifiDirect::isValidNetworkName(''))->toBeFalse()
            ->and(WifiDirect::isValidNetworkName('DIRECT-'))->toBeFalse()
            ->and(WifiDirect::isValidNetworkName('DIRECT-a-'))->toBeFalse()
            ->and(WifiDirect::isValidNetworkName('DIRECT-ab-'.str_repeat('x', 23)))->toBeFalse()
            ->and(WifiDirect::isValidNetworkName("DIRECT-ab\r"))->toBeFalse()
            ->and(WifiDirect::isValidNetworkName("DIRECT-ab\u{2028}"))->toBeFalse()
            ->and(WifiDirect::isValidNetworkName("DIRECT-ab\xff"))->toBeFalse();
    });
});

it('works through the facade with the documented signatures', function () {
    Facade::clearResolvedInstances();
    Facade::setFacadeApplication(new ArrayObject(['protendai.wifi-direct' => new WifiDirect]));
    Bridge::$fake->withWifiDirect();

    expect(WifiDirectFacade::status()['supported'])->toBeTrue()
        ->and(WifiDirectFacade::connect('DIRECT-ab-x', 'secret123'))->toBeTrue()
        ->and(WifiDirectFacade::parsePairing('nope'))->toBeNull()
        ->and(WifiDirectFacade::isValidPassphrase('secret123'))->toBeTrue();

    WifiDirectFacade::createGroup('auto', 30);

    Bridge::$fake->assertWifiDirectHosting(fn (array $p) => $p['band'] === 'auto' && $p['timeoutSeconds'] === 30)
        ->assertWifiDirectJoining(fn (array $p) => $p['networkName'] === 'DIRECT-ab-x');

    Facade::setFacadeApplication(null);
});
