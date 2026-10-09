<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Creating or joining a group failed. Reasons: permission, unsupported, wifi off,
 * location off, busy, error, timeout, group not ready. $detail is a one-line
 * diagnostic (raw reason, radio/location state, Android version, device model).
 */
class ConnectionFailed
{
    use Dispatchable;

    public function __construct(
        public ?string $reason = null,
        public ?string $detail = null,
    ) {}
}
