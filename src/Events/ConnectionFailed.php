<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Creating or joining a group failed. Reasons: permission, unsupported, wifi off,
 * location off, busy, error, invalid, timeout, group not ready, needs network,
 * cannot host (iOS), declined (iOS). $detail is a one-line diagnostic (raw
 * reason, radio/location state, OS version, device model); it never contains
 * the network name or passphrase.
 */
class ConnectionFailed
{
    use Dispatchable;

    public function __construct(
        public ?string $reason = null,
        public ?string $detail = null,
    ) {}
}
