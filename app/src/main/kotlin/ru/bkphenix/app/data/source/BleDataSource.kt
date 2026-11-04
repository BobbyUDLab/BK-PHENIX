package ru.bkphenix.app.data.source

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import ru.bkphenix.app.data.model.BleSnapshot
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.permissions.Permissions
import java.util.concurrent.ConcurrentHashMap

/** Сканирование BLE-маяков. Имя берётся из рекламного пакета, без BLUETOOTH_CONNECT. */
// Разрешения проверяются через Permissions, SecurityException перехватывается в guarded().
@SuppressLint("MissingPermission")
class BleDataSource(private val context: Context) : DataSource("Bluetooth/BLE") {

    @SuppressLint("MissingPermission")
    fun beacons(
        publishIntervalMillis: Long = 2_000,
        maxAgeMillis: Long = 90_000,
        scanWindowMillis: Long = 8_000,
        scanPeriodMillis: Long = 60_000,
    ): Flow<List<BleSnapshot>> =
        callbackFlow {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                setStatus(SourceStatus.Unsupported("BLE не поддерживается"))
                awaitClose { }
                return@callbackFlow
            }
            if (!Permissions.hasBluetoothScan(context)) {
                setStatus(SourceStatus.PermissionDenied)
                awaitClose { }
                return@callbackFlow
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val scanner = if (adapter?.isEnabled == true) adapter.bluetoothLeScanner else null
            if (scanner == null) {
                setStatus(SourceStatus.Unavailable("Bluetooth выключен"))
                awaitClose { }
                return@callbackFlow
            }
            val seen = ConcurrentHashMap<String, Pair<BleSnapshot, Long>>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val address = result.device?.address ?: return
                    val tx = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                    seen[address] = BleSnapshot(address, result.scanRecord?.deviceName, result.rssi, tx) to
                        System.currentTimeMillis()
                }

                override fun onScanFailed(errorCode: Int) {
                    setStatus(SourceStatus.Unavailable("Ошибка сканирования BLE, код $errorCode"))
                }
            }
            // Короткие окна вместо постоянного скана: Huawei/EMUI предупреждает о «постоянном поиске
            // Bluetooth-устройств в фоне» и может убить процесс. 8 с из каждых 60 с, режим LOW_POWER.
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
            val dutyCycle = launch {
                while (true) {
                    if (guarded("startScan") { scanner.startScan(null, settings, callback) } != null) {
                        scanning.set(true)
                        setStatus(SourceStatus.Active)
                    }
                    delay(scanWindowMillis)
                    if (scanning.getAndSet(false)) guarded("stopScan") { scanner.stopScan(callback) }
                    delay((scanPeriodMillis - scanWindowMillis).coerceAtLeast(0))
                }
            }
            val publisher = launch {
                while (true) {
                    delay(publishIntervalMillis)
                    val now = System.currentTimeMillis()
                    seen.entries.removeIf { now - it.value.second > maxAgeMillis }
                    trySend(seen.values.map { it.first }.sortedByDescending { it.rssiDbm })
                }
            }
            awaitClose {
                publisher.cancel()
                dutyCycle.cancel()
                if (scanning.getAndSet(false)) guarded("stopScan") { scanner.stopScan(callback) }
            }
        }
}
