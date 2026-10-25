<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * The group link ended: disconnect() was called (always answered with this
 * event, link or not), the other side left, the last member left a group we
 * host, a group we host went away, or a new createGroup()/connect() replaced
 * a link that had been reported with Connected.
 */
class Disconnected
{
    use Dispatchable;

    public function __construct() {}
}
