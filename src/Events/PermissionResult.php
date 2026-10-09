<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Answer to requestPermission().
 */
class PermissionResult
{
    use Dispatchable;

    public function __construct(
        public bool $granted = false,
    ) {}
}
