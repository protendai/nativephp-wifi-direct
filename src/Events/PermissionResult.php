<?php

namespace Protendai\WifiDirect\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Answer to requestPermission().
 *
 * When denied, $reason is "denied", "permanently denied" (best guess: Android
 * will not show the prompt again, so send the user to the app settings),
 * "approximate" (Android 12: only approximate location was allowed; Wi-Fi
 * Direct needs precise) or "interrupted" (the request could not be shown).
 */
class PermissionResult
{
    use Dispatchable;

    public function __construct(
        public bool $granted = false,
        public ?string $reason = null,
        public bool $permanentlyDenied = false,
    ) {}
}
