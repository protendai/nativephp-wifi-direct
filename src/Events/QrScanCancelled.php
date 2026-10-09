<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * The code scanner closed without a result ($reason: cancelled, or the failure message).
 */
class QrScanCancelled
{
    use Dispatchable;

    public function __construct(
        public ?string $reason = null,
    ) {}
}
