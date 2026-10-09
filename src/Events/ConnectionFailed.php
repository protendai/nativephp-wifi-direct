<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Creating or joining a group failed. Reasons: permission, unsupported, busy, error, timeout, group not ready.
 */
class ConnectionFailed
{
    use Dispatchable;

    public function __construct(
        public ?string $reason = null,
    ) {}
}
