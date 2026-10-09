<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * A group link is up: we joined one, or (as owner) a member joined ours.
 */
class Connected
{
    use Dispatchable;

    public function __construct(
        public bool $isGroupOwner = false,
        public ?string $groupOwnerIp = null,
        public int $clients = 0,
        public int $frequency = 0,
        public bool $connected = true,
    ) {}
}
