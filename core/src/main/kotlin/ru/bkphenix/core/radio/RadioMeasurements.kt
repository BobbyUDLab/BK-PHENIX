package ru.bkphenix.core.radio

import ru.bkphenix.core.geo.GeoPoint

/** Технология сотовой сети. */
public enum class CellTechnology { GSM, WCDMA, LTE, NR }

/**
 * Измерение по сотовой вышке (из AOSP CellInfo).
 *
 * @property pci физический идентификатор соты (PCI/PSC/BSIC).
 * @property rsrpDbm мощность опорного сигнала (для GSM — RSSI), дБм.
 * @property timingAdvance Timing Advance, если сеть его сообщает.
 */
public data class CellMeasurement(
    val technology: CellTechnology,
    val mcc: Int?,
    val mnc: Int?,
    val areaCode: Int?,
    val cellId: Long?,
    val pci: Int?,
    val rsrpDbm: Int?,
    val timingAdvance: Int?,
    val isServing: Boolean,
)

/** Запись офлайн-базы вышек (OpenCellID / дамп Mozilla Location Service). */
public data class CellTowerRecord(
    val technology: CellTechnology,
    val mcc: Int,
    val mnc: Int,
    val areaCode: Int,
    val cellId: Long,
    val position: GeoPoint,
    val rangeM: Double,
)

/** Офлайн-база вышек. */
public fun interface CellTowerDatabase {
    public fun find(measurement: CellMeasurement): CellTowerRecord?
}

/** Измерение Wi-Fi: RSSI и, при поддержке 802.11mc, дальность RTT. */
public data class WifiMeasurement(
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val rttDistanceM: Double? = null,
    val rttStdDevM: Double? = null,
)

/** Измерение BLE-маяка. */
public data class BleBeaconMeasurement(val address: String, val rssiDbm: Int, val txPowerDbm: Int?)

/** Дальность до одной точки по Timing Advance, метры (шаг TA зависит от технологии). */
public fun timingAdvanceDistanceM(technology: CellTechnology, timingAdvance: Int): Double? = when (technology) {
    CellTechnology.GSM -> timingAdvance * 553.85
    CellTechnology.LTE -> timingAdvance * 78.12
    CellTechnology.NR, CellTechnology.WCDMA -> null
}
