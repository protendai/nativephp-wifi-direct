package com.protendai.wifidirect

import android.graphics.Bitmap
import android.graphics.Color
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nativephp.mobile.bridge.BridgeFunction
import org.json.JSONObject
import java.io.File

/**
 * Bridge functions for protendai/nativephp-wifi-direct. Namespace: "WifiDirect.*".
 *
 * Each function is thin and returns immediately; anything asynchronous
 * (permission prompts, group creation, connecting, scanning) reports back to
 * PHP as an event under Protendai\WifiDirect\Events\*.
 */
object WifiDirectFunctions {

    /** Capabilities and current state: supported, fiveGhz, permission, connected... */
    class Status(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            WifiDirectManager.attach(activity)
            return WifiDirectManager.status()
        }
    }

    /** Ask for NEARBY_WIFI_DEVICES (Android 13+) or location (older). Result: PermissionResult. */
    class RequestPermission(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            WifiDirectManager.attach(activity)
            if (WifiDirectManager.hasPermission()) {
                WifiDirectManager.dispatch("PermissionResult", JSONObject().put("granted", true))
                return mapOf("ok" to true, "granted" to true)
            }
            PermissionFragment.request(activity, WifiDirectManager.requiredPermissions()) { granted ->
                WifiDirectManager.dispatch("PermissionResult", JSONObject().put("granted", granted))
            }
            return mapOf("ok" to true)
        }
    }

    /**
     * Host a group (this device becomes the group owner at 192.168.49.1).
     * Params: band ("5ghz" | "2.4ghz" | "auto"), timeoutSeconds (default 20).
     * Result: GroupCreated or ConnectionFailed (by the deadline at the latest).
     */
    class CreateGroup(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            WifiDirectManager.attach(activity)
            val band = parameters["band"] as? String ?: "5ghz"
            val timeout = (parameters["timeoutSeconds"] as? Number)?.toLong() ?: 20L
            return mapOf("ok" to WifiDirectManager.createGroup(band, timeout * 1000))
        }
    }

    /**
     * Join a group by its network name and passphrase, without the system
     * "accept invitation" dialog. Result: Connected or ConnectionFailed.
     */
    class Connect(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            WifiDirectManager.attach(activity)
            val name = parameters["networkName"] as? String ?: return mapOf("ok" to false)
            val passphrase = parameters["passphrase"] as? String ?: return mapOf("ok" to false)
            val band = parameters["band"] as? String ?: "auto"
            return mapOf("ok" to WifiDirectManager.connect(name, passphrase, band))
        }
    }

    /** Leave or tear down the current group. Result: Disconnected. */
    class Disconnect(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            WifiDirectManager.attach(activity)
            WifiDirectManager.disconnect()
            return mapOf("ok" to true)
        }
    }

    /**
     * Render text as a QR code PNG at `dest`. Params: text, dest, size (px),
     * foreground / background ("#RRGGBB"). Synchronous.
     */
    class QrCode(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val text = parameters["text"] as? String ?: return mapOf("ok" to false)
            val dest = parameters["dest"] as? String ?: return mapOf("ok" to false)
            val size = (parameters["size"] as? Number)?.toInt() ?: 640
            val foreground = parseColor(parameters["foreground"] as? String, Color.BLACK)
            val background = parseColor(parameters["background"] as? String, Color.WHITE)

            val ok = runCatching {
                val matrix = QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, size, size,
                    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
                )
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix.get(i % matrix.width, i / matrix.width)) foreground else background
                }
                val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                File(dest).parentFile?.mkdirs()
                File(dest).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                true
            }.getOrDefault(false)

            return mapOf("ok" to ok)
        }

        private fun parseColor(hex: String?, fallback: Int): Int =
            hex?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback
    }

    /**
     * Open Google's code scanner (Play services UI, no camera permission
     * needed). Result: QrScanned { text } or QrScanCancelled { reason }.
     */
    class ScanQr(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            WifiDirectManager.attach(activity)
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            val scanner = GmsBarcodeScanning.getClient(activity, options)

            activity.runOnUiThread {
                // First use may need the scanner module downloaded by Play services.
                ModuleInstall.getClient(activity)
                    .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                    .addOnCompleteListener {
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                WifiDirectManager.dispatch("QrScanned", JSONObject().put("text", barcode.rawValue ?: ""))
                            }
                            .addOnCanceledListener {
                                WifiDirectManager.dispatch("QrScanCancelled", JSONObject().put("reason", "cancelled"))
                            }
                            .addOnFailureListener { e ->
                                WifiDirectManager.dispatch("QrScanCancelled", JSONObject().put("reason", e.message ?: "failed"))
                            }
                    }
            }
            return mapOf("ok" to true)
        }
    }
}
