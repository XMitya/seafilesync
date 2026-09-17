package com.xmitya.seafilesync.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.xmitya.seafilesync.data.api.SeafileApi

/**
 * The device identity sent at login, which is what the server lists under the account's devices
 * and what a remote wipe targets.
 *
 * The server validates android device ids as 1..16 lowercase hex characters, which is exactly the
 * shape of ANDROID_ID. It can still come back null or malformed on unusual builds, so the value
 * is normalised and padded rather than trusted.
 */
object DeviceIdentity {

    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val hex = androidId
            .orEmpty()
            .lowercase()
            .filter { it in "0123456789abcdef" }
            .take(16)
        // A device that reports nothing usable still needs a stable, well-formed id.
        return hex.ifEmpty { fallbackId(context) }
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        .trim()
        .take(SeafileApi.DEVICE_NAME_MAX)

    fun platformVersion(): String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    private fun fallbackId(context: Context): String {
        val seed = "${context.packageName}:${Build.FINGERPRINT}".hashCode().toLong() and 0xFFFFFFFFL
        return seed.toString(16).padStart(16, '0').take(16)
    }
}
