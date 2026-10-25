/** Types for @protendai/nativephp-wifi-direct. */

export const OWNER_IP: '192.168.49.1';

export const Events: Readonly<{
    PermissionResult: 'Protendai\\WifiDirect\\Events\\PermissionResult';
    GroupCreated: 'Protendai\\WifiDirect\\Events\\GroupCreated';
    Connected: 'Protendai\\WifiDirect\\Events\\Connected';
    Disconnected: 'Protendai\\WifiDirect\\Events\\Disconnected';
    ConnectionFailed: 'Protendai\\WifiDirect\\Events\\ConnectionFailed';
    QrScanned: 'Protendai\\WifiDirect\\Events\\QrScanned';
    QrScanCancelled: 'Protendai\\WifiDirect\\Events\\QrScanCancelled';
}>;

export type WifiDirectEvent = (typeof Events)[keyof typeof Events];
export type Band = '5ghz' | '2.4ghz' | 'auto';

export interface WifiDirectStatus {
    supported: boolean;
    canHost: boolean;
    enabled: boolean;
    fiveGhz: boolean;
    permission: boolean;
    wifiJoined: boolean;
    connected: boolean;
    isGroupOwner: boolean;
    groupOwnerIp: string;
}

export interface Pairing {
    networkName: string;
    passphrase: string;
    extra: Record<string, unknown>;
}

export interface PermissionResultPayload {
    granted: boolean;
    reason?: 'denied' | 'permanently denied' | 'approximate' | 'interrupted' | null;
    permanentlyDenied?: boolean;
}

export interface GroupCreatedPayload {
    networkName: string;
    passphrase: string;
    ownerIp: string;
    frequency: number;
    band: '5ghz' | '2.4ghz';
}

export interface ConnectedPayload {
    connected: true;
    isGroupOwner: boolean;
    groupOwnerIp: string | null;
    clients: number;
    frequency: number;
}

export interface ConnectionFailedPayload {
    reason: string | null;
    detail: string | null;
}

export interface QrScannedPayload {
    text: string;
}

export interface QrScanCancelledPayload {
    reason: string | null;
}

export interface EventPayloads {
    'Protendai\\WifiDirect\\Events\\PermissionResult': PermissionResultPayload;
    'Protendai\\WifiDirect\\Events\\GroupCreated': GroupCreatedPayload;
    'Protendai\\WifiDirect\\Events\\Connected': ConnectedPayload;
    'Protendai\\WifiDirect\\Events\\Disconnected': Record<string, never>;
    'Protendai\\WifiDirect\\Events\\ConnectionFailed': ConnectionFailedPayload;
    'Protendai\\WifiDirect\\Events\\QrScanned': QrScannedPayload;
    'Protendai\\WifiDirect\\Events\\QrScanCancelled': QrScanCancelledPayload;
}

export function status(): Promise<WifiDirectStatus>;
export function requestPermission(): Promise<{ ok: boolean; granted: boolean }>;
export function createGroup(band?: Band, timeoutSeconds?: number): Promise<{ ok: boolean }>;
export function connect(networkName: string, passphrase: string, band?: Band): Promise<{ ok: boolean; error?: string }>;
export function disconnect(): Promise<{ ok: boolean }>;
export function qrCode(text: string, dest: string, size?: number, foreground?: string, background?: string): Promise<{ ok: boolean }>;
export function scanQr(): Promise<{ ok: boolean }>;
export function pairingPayload(networkName: string, passphrase: string, extra?: Record<string, unknown>): string;
export function parsePairing(text: string): Pairing | null;
export function isValidNetworkName(networkName: string): boolean;
export function isValidPassphrase(passphrase: string): boolean;
export function onWifiDirect<E extends WifiDirectEvent>(event: E, callback: (payload: EventPayloads[E], event: E) => void): () => void;

export const WifiDirect: {
    status: typeof status;
    requestPermission: typeof requestPermission;
    createGroup: typeof createGroup;
    connect: typeof connect;
    disconnect: typeof disconnect;
    qrCode: typeof qrCode;
    scanQr: typeof scanQr;
    pairingPayload: typeof pairingPayload;
    parsePairing: typeof parsePairing;
    isValidNetworkName: typeof isValidNetworkName;
    isValidPassphrase: typeof isValidPassphrase;
    on: typeof onWifiDirect;
    Events: typeof Events;
    OWNER_IP: typeof OWNER_IP;
};

export default WifiDirect;
