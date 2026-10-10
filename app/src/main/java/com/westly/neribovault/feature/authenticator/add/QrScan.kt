package com.westly.neribovault.feature.authenticator.add

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private const val MSG_NEEDS_DOWNLOAD =
    "The scanner needs to download once. Connect to the internet and try again, or paste the link instead."
private const val MSG_NOT_AVAILABLE =
    "Scanning isn't available right now. Paste the link or type the key instead."

/**
 * Opens Google's code scanner (run by Google Play services; no camera permission is needed).
 * [onScanned] gets the raw text of the QR code; it must not be logged or kept. [onCancelled] is
 * called when the person closes the scanner, [onFailed] when it cannot run.
 */
fun startQrScan(
    context: Context,
    onScanned: (String) -> Unit,
    onCancelled: () -> Unit,
    onFailed: (Exception) -> Unit,
) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    try {
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw == null) {
                    onFailed(IllegalStateException("Empty code"))
                } else {
                    onScanned(raw)
                }
            }
            .addOnCanceledListener { onCancelled() }
            .addOnFailureListener { error -> onFailed(error) }
    } catch (e: Exception) {
        onFailed(e)
    }
}

/** The short message shown in the sheet when the scanner fails. */
fun scanFailureMessage(error: Exception): String {
    val needsDownload = error is MlKitException &&
        (error.errorCode == MlKitException.UNAVAILABLE || error.errorCode == MlKitException.NETWORK_ISSUE)
    return if (needsDownload) MSG_NEEDS_DOWNLOAD else MSG_NOT_AVAILABLE
}
