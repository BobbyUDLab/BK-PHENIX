package ru.bkphenix.core.integrity

/**
 * Гистерезис тревог: тревожное состояние объявляется после [raiseEpochs] эпох подряд, снимается после
 * [clearEpochs] спокойных эпох подряд. Пока тревога не подтверждена, точка не выдаётся за надёжную:
 * состояние понижается до [IntegrityState.SUSPICIOUS] с причиной «кратковременно: …».
 *
 * Состояния, требующие немедленной реакции ([immediate]), пропускаются без задержки.
 * Журнал 2026-11-04: одиночные эпохи FAULT RAIM и детекторов у домов давали ~100 эпизодов в час.
 */
public class AlarmFilter(
    private val raiseEpochs: Int = 3,
    private val clearEpochs: Int = 3,
    private val immediate: Set<IntegrityState> = setOf(IntegrityState.NAV_DATA_CORRUPTED),
) {
    private var pending = 0
    private var calm = 0
    private var active: IntegrityReport? = null

    public fun filter(report: IntegrityReport): IntegrityReport {
        val alarm = report.state in ALARM_STATES
        if (alarm) {
            calm = 0
            pending++
            if (report.state in immediate || pending >= raiseEpochs || active != null) {
                active = report
                return report
            }
            return IntegrityReport(IntegrityState.SUSPICIOUS, report.reasons.map { "кратковременно: $it" })
        }
        pending = 0
        val current = active ?: return report
        calm++
        if (calm >= clearEpochs) {
            active = null
            calm = 0
            return report
        }
        return IntegrityReport.merge(listOf(report, IntegrityReport(IntegrityState.SUSPICIOUS, listOf("снимается тревога: " + current.state.description))))
    }

    public fun reset() {
        pending = 0
        calm = 0
        active = null
    }

    public companion object {
        /** Состояния, которые считаются тревогой (в метриках ложных тревог тоже). */
        public val ALARM_STATES: Set<IntegrityState> = setOf(
            IntegrityState.INCONSISTENT, IntegrityState.GNSS_LOST,
            IntegrityState.NAV_DATA_CORRUPTED, IntegrityState.SPOOFING_SUSPECTED,
        )
    }
}
