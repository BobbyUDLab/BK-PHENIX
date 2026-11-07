package ru.bkphenix.app.data.mapping

import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.core.radio.CellMeasurement

object CellMapping {
    /** Значение android.telephony.CellInfo.UNAVAILABLE (Integer.MAX_VALUE). */
    const val UNAVAILABLE: Int = Int.MAX_VALUE

    /** Значение CellInfo.UNAVAILABLE_LONG (Long.MAX_VALUE). */
    const val UNAVAILABLE_LONG: Long = Long.MAX_VALUE

    fun intOrNull(value: Int): Int? = value.takeUnless { it == UNAVAILABLE || it == Int.MIN_VALUE }

    fun longOrNull(value: Long): Long? = value.takeUnless { it == UNAVAILABLE_LONG || it == Long.MIN_VALUE }

    /** MCC/MNC в API 28+ приходят строкой, бывают `null` или пустыми. */
    fun codeOrNull(value: String?): Int? = value?.trim()?.toIntOrNull()

    fun toMeasurement(cell: CellSnapshot): CellMeasurement = CellMeasurement(
        technology = cell.technology,
        mcc = cell.mcc,
        mnc = cell.mnc,
        areaCode = cell.areaCode,
        cellId = cell.cellId,
        pci = cell.pci,
        rsrpDbm = cell.signalDbm,
        timingAdvance = cell.timingAdvance,
        isServing = cell.isServing,
        ageMillis = cell.ageMillis,
    )
}
