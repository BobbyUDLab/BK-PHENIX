package ru.bkphenix.core.terrain

import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.SourceGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Запрет GNSS по грубой ошибке высоты.
 *
 * Подмена у Шереметьево во всех полевых журналах (04–07.11.2026, Mate 70, DOOGEE, P30 lite) выдаёт
 * высоту на 770–820 м выше рельефа. На P30 lite 07.11 других источников не было (нет сети, сот,
 * сырых измерений), и подменённая точка была итогом всей прогулки, хотя проверка высоты
 * показывала +780 м. Такой разрыв не объясняется ни ошибкой высоты телефона (20–40 м), ни этажом
 * (крыши самых высоких домов Москвы ниже 400 м), поэтому горизонталь GNSS тоже не принимается.
 *
 * Включается после [enterEpochs] эпох подряд с |невязкой| > [thresholdM], снимается после
 * [exitEpochs] эпох подряд с нормальной высотой. Эпоха без проверки (нет высоты или рельефа)
 * состояние не меняет.
 */
public class GrossAltitudeVeto(
    private val thresholdM: Double = THRESHOLD_M,
    private val enterEpochs: Int = ENTER_EPOCHS,
    private val exitEpochs: Int = EXIT_EPOCHS,
) {
    private var bad = 0
    private var good = 0
    private var lastResidualM: Double? = null

    /** GNSS сейчас под запретом. */
    public var active: Boolean = false
        private set

    /** Учитывает невязку высоты эпохи; true — запрет только что включился. */
    public fun update(residualM: Double?): Boolean {
        val r = residualM ?: return false
        lastResidualM = r
        if (abs(r) > thresholdM) {
            bad++
            good = 0
        } else {
            good++
            bad = 0
        }
        val wasActive = active
        if (!active && bad >= enterEpochs) active = true
        if (active && good >= exitEpochs) active = false
        return active && !wasActive
    }

    /** Убирает из наблюдений всю группу GNSS-сигнала, если запрет активен. */
    public fun filter(observations: List<PositionObservation>): List<PositionObservation> =
        if (!active) observations else observations.filterNot { SourceGroup.GNSS_SIGNAL in it.origin }

    /** Отчёт для итога эпохи, пока запрет активен. */
    public fun report(): IntegrityReport? {
        if (!active) return null
        val r = lastResidualM?.let { abs(it).roundToInt() }
        val dir = if ((lastResidualM ?: 0.0) >= 0) "выше" else "ниже"
        return IntegrityReport(
            IntegrityState.SPOOFING_SUSPECTED,
            listOf("Высота ГНСС на $r м $dir рельефа — позиция ГНСС отброшена как подменённая; без других источников поставьте якорь"),
        )
    }

    public fun reset() {
        active = false
        bad = 0
        good = 0
        lastResidualM = null
    }

    public companion object {
        public const val THRESHOLD_M: Double = 400.0
        public const val ENTER_EPOCHS: Int = 3
        public const val EXIT_EPOCHS: Int = 10
    }
}
