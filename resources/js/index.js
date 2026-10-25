/**
 * Wi-Fi Direct for NativePHP Mobile: JavaScript API for Inertia (Vue, React)
 * and other SPA front ends. Mirrors the PHP facade Protendai\WifiDirect\Facades\WifiDirect.
 *
 * Every call returns straight away; slow work (permission prompts, creating
 * or joining a group, scanning a code) answers later with an event. Listen
 * with onWifiDirect() (or On() from '@nativephp/mobile') and the Events names.
 *
 * @example
 * import { connect, scanQr, parsePairing, onWifiDirect, Events } from '@protendai/nativephp-wifi-direct';
 *
 * onWifiDirect(Events.QrScanned, async ({ text }) => {
 *     const pairing = parsePairing(text);
 *     if (pairing) await connect(pairing.networkName, pairing.passphrase);
 * });
 * onWifiDirect(Events.Connected, ({ groupOwnerIp }) => console.log('host at', groupOwnerIp));
 *
 * await scanQr();
 */

const baseUrl = '/_native/api/call';

/** The group owner's address on every Wi-Fi Direct group. */
export const OWNER_IP = '192.168.49.1';

/**
 * Fully qualified event class names, as dispatched by the native side.
 * @readonly
 */
export const Events = Object.freeze({
    PermissionResult: 'Protendai\\WifiDirect\\Events\\PermissionResult',
    GroupCreated: 'Protendai\\WifiDirect\\Events\\GroupCreated',
    Connected: 'Protendai\\WifiDirect\\Events\\Connected',
    Disconnected: 'Protendai\\WifiDirect\\Events\\Disconnected',
    ConnectionFailed: 'Protendai\\WifiDirect\\Events\\ConnectionFailed',
    QrScanned: 'Protendai\\WifiDirect\\Events\\QrScanned',
    QrScanCancelled: 'Protendai\\WifiDirect\\Events\\QrScanCancelled',
});

/**
 * Call a bridge function through NativePHP's /_native/api/call endpoint.
 * @param {string} method
 * @param {Object} [params]
 * @returns {Promise<Object>} the function's result data
 * @private
 */
async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content || '',
        },
        body: JSON.stringify({ method, params }),
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new Error(result.message || `${method} failed`);
    }

    return result.data ?? {};
}

/**
 * Capabilities and current state.
 * @returns {Promise<{supported: boolean, canHost: boolean, enabled: boolean, fiveGhz: boolean, permission: boolean, wifiJoined: boolean, connected: boolean, isGroupOwner: boolean, groupOwnerIp: string}>}
 */
export async function status() {
    return bridgeCall('WifiDirect.Status');
}

/**
 * Ask for "Nearby Wi-Fi devices" (Android 13+) or location (Android 10-12).
 * Answers with Events.PermissionResult (immediately when already granted).
 * @returns {Promise<{ok: boolean, granted: boolean}>}
 */
export async function requestPermission() {
    return bridgeCall('WifiDirect.RequestPermission');
}

/**
 * Host a group (Android). Cancels anything in flight first.
 * Answers with Events.GroupCreated or Events.ConnectionFailed within timeoutSeconds.
 * @param {'5ghz'|'2.4ghz'|'auto'} [band]
 * @param {number} [timeoutSeconds]
 * @returns {Promise<{ok: boolean}>}
 */
export async function createGroup(band = '5ghz', timeoutSeconds = 20) {
    return bridgeCall('WifiDirect.CreateGroup', { band, timeoutSeconds });
}

/**
 * Join a group silently. Cancels anything in flight first.
 * Answers with Events.Connected or Events.ConnectionFailed (30 s at most).
 * Malformed credentials resolve to {ok: false, error} and fire no event.
 * @param {string} networkName
 * @param {string} passphrase
 * @param {'5ghz'|'2.4ghz'|'auto'} [band]
 * @returns {Promise<{ok: boolean, error?: string}>}
 */
export async function connect(networkName, passphrase, band = 'auto') {
    if (!isValidNetworkName(networkName) || !isValidPassphrase(passphrase)) {
        return { ok: false, error: 'invalid network name or passphrase' };
    }

    return bridgeCall('WifiDirect.Connect', { networkName, passphrase, band });
}

/**
 * Leave or tear down the group and cancel anything in flight.
 * Always answers with Events.Disconnected.
 * @returns {Promise<{ok: boolean}>}
 */
export async function disconnect() {
    return bridgeCall('WifiDirect.Disconnect');
}

/**
 * Write text as a QR code PNG at dest (an absolute path on the device).
 * @param {string} text
 * @param {string} dest
 * @param {number} [size] pixels per side
 * @param {string} [foreground] '#RRGGBB'
 * @param {string} [background] '#RRGGBB'
 * @returns {Promise<{ok: boolean}>}
 */
export async function qrCode(text, dest, size = 640, foreground = '#000000', background = '#FFFFFF') {
    return bridgeCall('WifiDirect.QrCode', { text, dest, size, foreground, background });
}

/**
 * Open the QR code scanner. Answers with Events.QrScanned or Events.QrScanCancelled.
 * @returns {Promise<{ok: boolean}>}
 */
export async function scanQr() {
    return bridgeCall('WifiDirect.ScanQr');
}

/**
 * The QR payload for a created group plus your own data (e.g. a port).
 * Same format as the PHP pairingPayload().
 * @param {string} networkName
 * @param {string} passphrase
 * @param {Object} [extra]
 * @returns {string}
 */
export function pairingPayload(networkName, passphrase, extra = {}) {
    return JSON.stringify({ wd: 1, n: networkName, p: passphrase, ...extra });
}

/**
 * Parse a scanned pairing payload; null when it isn't one or its credentials are malformed.
 * @param {string} text
 * @returns {{networkName: string, passphrase: string, extra: Object}|null}
 */
export function parsePairing(text) {
    let data;
    try {
        data = JSON.parse(text);
    } catch {
        return null;
    }

    if (!data || typeof data !== 'object' || Array.isArray(data) || data.wd !== 1
        || typeof data.n !== 'string' || typeof data.p !== 'string'
        || !isValidNetworkName(data.n) || !isValidPassphrase(data.p)) {
        return null;
    }

    const { wd, n, p, ...extra } = data;
    return { networkName: n, passphrase: p, extra };
}

/**
 * "DIRECT-" plus two letters or digits, at most 32 bytes of UTF-8.
 * @param {string} networkName
 * @returns {boolean}
 */
export function isValidNetworkName(networkName) {
    return typeof networkName === 'string'
        && new TextEncoder().encode(networkName).length <= 32
        && /^DIRECT-[a-zA-Z0-9]{2}[^\n\r\u0085\u2028\u2029]*$/.test(networkName);
}

/**
 * A WPA2 passphrase: 8 to 63 printable ASCII characters.
 * @param {string} passphrase
 * @returns {boolean}
 */
export function isValidPassphrase(passphrase) {
    return typeof passphrase === 'string' && /^[\x20-\x7E]{8,63}$/.test(passphrase);
}

/**
 * Listen for a Wi-Fi Direct event. Returns a function that stops listening.
 * @param {string} event one of Events
 * @param {(payload: Object, event: string) => void} callback
 * @returns {() => void}
 */
export function onWifiDirect(event, callback) {
    const listener = (e) => {
        const name = String(e.detail?.event ?? '').replace(/^\\+/, '');
        if (name === event) {
            callback(e.detail.payload ?? {}, name);
        }
    };

    document.addEventListener('native-event', listener);
    return () => document.removeEventListener('native-event', listener);
}

export const WifiDirect = {
    status,
    requestPermission,
    createGroup,
    connect,
    disconnect,
    qrCode,
    scanQr,
    pairingPayload,
    parsePairing,
    isValidNetworkName,
    isValidPassphrase,
    on: onWifiDirect,
    Events,
    OWNER_IP,
};

export default WifiDirect;
