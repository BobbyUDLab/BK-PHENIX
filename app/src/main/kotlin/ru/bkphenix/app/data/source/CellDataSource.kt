package ru.bkphenix.app.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.bkphenix.app.data.mapping.CellMapping.codeOrNull
import ru.bkphenix.app.data.mapping.CellMapping.intOrNull
import ru.bkphenix.app.data.mapping.CellMapping.longOrNull
import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.permissions.Permissions
import ru.bkphenix.core.radio.CellTechnology

/** Сотовые вышки из TelephonyManager.getAllCellInfo: LTE, NR, GSM, WCDMA. */
// Разрешения проверяются через Permissions, SecurityException перехватывается в guarded().
@SuppressLint("MissingPermission")
class CellDataSource(private val context: Context) : DataSource("Сотовые вышки") {
    private val manager = context.getSystemService(TelephonyManager::class.java)

    fun cells(intervalMillis: Long = 5_000): Flow<List<CellSnapshot>> = flow {
        val tm = manager
        if (tm == null) {
            setStatus(SourceStatus.Unsupported("Нет модуля телефонии"))
            return@flow
        }
        while (true) {
            if (!Permissions.hasFineLocation(context)) {
                setStatus(SourceStatus.PermissionDenied)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    guarded("requestCellInfoUpdate") {
                        tm.requestCellInfoUpdate(
                            ContextCompat.getMainExecutor(context),
                            object : TelephonyManager.CellInfoCallback() {
                                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = Unit
                            },
                        )
                    }
                }
                val infos = guarded("getAllCellInfo") { tm.allCellInfo }
                if (infos != null) {
                    setStatus(SourceStatus.Active)
                    emit(infos.mapNotNull { it.toSnapshot() })
                } else if (status.value !is SourceStatus.PermissionDenied) {
                    setStatus(SourceStatus.Unavailable("getAllCellInfo вернул пусто"))
                }
            }
            delay(intervalMillis)
        }
    }

    @Suppress("DEPRECATION")
    private fun CellInfo.toSnapshot(): CellSnapshot? {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return when (this) {
            is CellInfoLte -> CellSnapshot(
                technology = CellTechnology.LTE,
                isServing = isRegistered,
                mcc = if (modern) codeOrNull(cellIdentity.mccString) else intOrNull(cellIdentity.mcc),
                mnc = if (modern) codeOrNull(cellIdentity.mncString) else intOrNull(cellIdentity.mnc),
                areaCode = intOrNull(cellIdentity.tac),
                cellId = intOrNull(cellIdentity.ci)?.toLong(),
                pci = intOrNull(cellIdentity.pci),
                signalDbm = intOrNull(cellSignalStrength.rsrp),
                timingAdvance = intOrNull(cellSignalStrength.timingAdvance),
            )
            is CellInfoGsm -> CellSnapshot(
                technology = CellTechnology.GSM,
                isServing = isRegistered,
                mcc = if (modern) codeOrNull(cellIdentity.mccString) else intOrNull(cellIdentity.mcc),
                mnc = if (modern) codeOrNull(cellIdentity.mncString) else intOrNull(cellIdentity.mnc),
                areaCode = intOrNull(cellIdentity.lac),
                cellId = intOrNull(cellIdentity.cid)?.toLong(),
                pci = intOrNull(cellIdentity.bsic),
                signalDbm = intOrNull(cellSignalStrength.dbm),
                timingAdvance = intOrNull(cellSignalStrength.timingAdvance),
            )
            is CellInfoWcdma -> CellSnapshot(
                technology = CellTechnology.WCDMA,
                isServing = isRegistered,
                mcc = if (modern) codeOrNull(cellIdentity.mccString) else intOrNull(cellIdentity.mcc),
                mnc = if (modern) codeOrNull(cellIdentity.mncString) else intOrNull(cellIdentity.mnc),
                areaCode = intOrNull(cellIdentity.lac),
                cellId = intOrNull(cellIdentity.cid)?.toLong(),
                pci = intOrNull(cellIdentity.psc),
                signalDbm = intOrNull(cellSignalStrength.dbm),
                timingAdvance = null,
            )
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is CellInfoNr) nrSnapshot() else null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun CellInfoNr.nrSnapshot(): CellSnapshot {
        val id = cellIdentity as CellIdentityNr
        val signal = cellSignalStrength as CellSignalStrengthNr
        return CellSnapshot(
            technology = CellTechnology.NR,
            isServing = isRegistered,
            mcc = codeOrNull(id.mccString),
            mnc = codeOrNull(id.mncString),
            areaCode = intOrNull(id.tac),
            cellId = longOrNull(id.nci),
            pci = intOrNull(id.pci),
            signalDbm = intOrNull(signal.ssRsrp),
            timingAdvance = null,
        )
    }
}
