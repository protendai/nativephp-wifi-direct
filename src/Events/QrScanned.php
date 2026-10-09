<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * The code scanner read a QR code.
 */
class QrScanned
{
    use Dispatchable;

    public function __construct(
        public string $text = '',
    ) {}
}
