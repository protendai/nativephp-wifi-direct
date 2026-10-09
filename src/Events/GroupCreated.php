<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * This device now hosts a group at $ownerIp; share $networkName/$passphrase (e.g. via a QR code).
 */
class GroupCreated
{
    use Dispatchable;

    public function __construct(
        public ?string $networkName = null,
        public ?string $passphrase = null,
        public string $ownerIp = '192.168.49.1',
        public int $frequency = 0,
        public ?string $band = null,
    ) {}
}
