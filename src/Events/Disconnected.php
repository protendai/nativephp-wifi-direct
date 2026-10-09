<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * The group link went away (we left, or the last member did).
 */
class Disconnected
{
    use Dispatchable;

    public function __construct() {}
}
