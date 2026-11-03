package ru.bkphenix.app.data.source

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.data.model.WifiSnapshot
import ru.bkphenix.app.permissions.Permissions

/**
 * Сканирование Wi-Fi (RSSI) и дальнометрия Wi-Fi RTT (IEEE 802.11mc).
 * Android ограничивает частоту startScan (4 раза за 2 минуты), поэтому интервал — 30 с.
 */
// Разрешения проверяются через Permissions, SecurityException перехватывается в guarded().
@SuppressLint("MissingPermission")
class WifiDataSource(private val context: Context) : DataSource("Wi-Fi") {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val rtt: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        ) {
            context.getSystemService(WifiRttManager::class.java)
        } else {
            null
        }

    private val _rttStatus = MutableStateFlow<SourceStatus>(
        if (rtt == null) SourceStatus.Unsupported("Wi-Fi RTT не поддерживается") else SourceStatus.Idle,
    )
    val rttStatus: StateFlow<SourceStatus> = _rttStatus.asStateFlow()

    fun scans(intervalMillis: Long = 30_000): Flow<List<WifiSnapshot>> = callbackFlow {
        val wm = wifi
        if (wm == null) {
            setStatus(SourceStatus.Unsupported("Нет Wi-Fi"))
            awaitClose { }
            return@callbackFlow
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val results = guarded("getScanResults") { readResults(wm) } ?: return
                setStatus(SourceStatus.Active)
                trySend(results)
                launch { rangeIfPossible(results)?.let { trySend(it) } }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val ticker = launch {
            while (true) {
                when {
                    !Permissions.hasFineLocation(context) -> setStatus(SourceStatus.PermissionDenied)
                    !canScan(wm) -> setStatus(SourceStatus.Unavailable("Wi-Fi выключен"))
                    else -> {
                        @Suppress("DEPRECATION")
                        guarded("startScan") { wm.startScan() }
                        guarded("getScanResults") { readResults(wm) }?.let { trySend(it) }
                    }
                }
                delay(intervalMillis)
            }
        }
        awaitClose {
            ticker.cancel()
            guarded("unregisterReceiver") { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION") // isScanAlwaysAvailable: замены в публичном API нет
    private fun canScan(wm: WifiManager): Boolean = wm.isWifiEnabled || wm.isScanAlwaysAvailable

    @SuppressLint("MissingPermission") // проверяется в Permissions, SecurityException ловится в guarded
    private fun readResults(wm: WifiManager): List<WifiSnapshot> = wm.scanResults.map { it.toSnapshot() }

    @Suppress("DEPRECATION")
    private fun ScanResult.toSnapshot() = WifiSnapshot(
        bssid = BSSID ?: "",
        ssid = SSID?.takeIf { it.isNotBlank() },
        rssiDbm = level,
        frequencyMhz = frequency,
        supportsRtt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && is80211mcResponder,
    )

    @SuppressLint("MissingPermission")
    private suspend fun rangeIfPossible(results: List<WifiSnapshot>): List<WifiSnapshot>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val manager = rtt ?: return null
        if (!Permissions.hasNearbyWifi(context) || !Permissions.hasFineLocation(context)) {
            _rttStatus.value = SourceStatus.PermissionDenied
            return null
        }
        if (!manager.isAvailable) {
            _rttStatus.value = SourceStatus.Unavailable("Wi-Fi RTT сейчас недоступен")
            return null
        }
        val scan = guarded("scanResults") { wifi?.scanResults }.orEmpty()
            .filter { it.is80211mcResponder }
            .take(RangingRequest.getMaxPeers())
        if (scan.isEmpty()) return null
        val request = RangingRequest.Builder().addAccessPoints(scan).build()
        val ranges = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val started = guarded("startRanging") {
                manager.startRanging(
                    request,
                    ContextCompat.getMainExecutor(context),
                    object : RangingResultCallback() {
                        override fun onRangingFailure(code: Int) {
                            _rttStatus.value = SourceStatus.Unavailable("Ошибка RTT, код $code")
                            if (cont.isActive) cont.resumeWith(Result.success(emptyList<RangingResult>()))
                        }

                        override fun onRangingResults(results: List<RangingResult>) {
                            _rttStatus.value = SourceStatus.Active
                            if (cont.isActive) cont.resumeWith(Result.success(results))
                        }
                    },
                )
            }
            if (started == null && cont.isActive) cont.resumeWith(Result.success(emptyList()))
        }
        val byBssid = ranges.filter { it.status == RangingResult.STATUS_SUCCESS }
            .associateBy { it.macAddress?.toString()?.lowercase() }
        return results.map { w ->
            byBssid[w.bssid.lowercase()]?.let {
                w.copy(rttDistanceM = it.distanceMm / 1000.0, rttStdDevM = it.distanceStdDevMm / 1000.0)
            } ?: w
        }
    }
}
