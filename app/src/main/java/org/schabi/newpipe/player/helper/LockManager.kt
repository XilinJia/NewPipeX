package org.schabi.newpipe.player.helper

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import androidx.core.content.ContextCompat
import org.schabi.newpipe.util.Logd

class LockManager(context: Context) {
    private val TAG = "LockManager@" + hashCode()

    private val powerManager = ContextCompat.getSystemService(context.applicationContext,
        PowerManager::class.java)
    private val wifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)

    private var wakeLock: WakeLock? = null
    private var wifiLock: WifiLock? = null

    fun acquireWifiAndCpu() {
        Logd(TAG, "acquireWifiAndCpu() called")
        if (wakeLock != null && wakeLock!!.isHeld && wifiLock != null && wifiLock!!.isHeld) {
            return
        }

        wakeLock = powerManager!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        wifiLock = wifiManager!!.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG)

        if (wakeLock != null) {
            wakeLock!!.acquire()
        }
        if (wifiLock != null) {
            wifiLock!!.acquire()
        }
    }

    fun releaseWifiAndCpu() {
        Logd(TAG, "releaseWifiAndCpu() called")
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }

        wakeLock = null
        wifiLock = null
    }
}
