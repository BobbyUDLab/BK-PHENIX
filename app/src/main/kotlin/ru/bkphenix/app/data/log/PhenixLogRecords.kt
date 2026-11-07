package ru.bkphenix.app.data.log

import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.app.data.model.MeasurementSummary
import ru.bkphenix.app.data.model.NetworkTimeSample
import ru.bkphenix.app.data.model.SensorSnapshot
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.inertial.MotionSummary
import ru.bkphenix.core.model.PositionObservation

/** Поля записей JSONL: одна запись `epoch` в секунду с результатами ядра и окружением. */
object PhenixLogRecords {
    fun epoch(
        solution: PhenixSolution,
        measurements: MeasurementSummary?,
        satellites: Int,
        satellitesFromMeasurements: Boolean,
        cells: List<CellSnapshot>,
        sensors: Collection<SensorSnapshot>,
        networkTime: NetworkTimeSample?,
        observations: List<PositionObservation> = emptyList(),
        motion: MotionSummary? = null,
        gnssSpeedMps: Double? = null,
    ): Map<String, Any?> = linkedMapOf(
        "point" to solution.point?.let(::point),
        "accuracyM" to solution.accuracyM,
        "integrity" to mapOf("state" to solution.integrity.state.name, "reasons" to solution.integrity.reasons),
        "anchor" to mapOf(
            "state" to solution.anchor.state.name,
            "deviationM" to solution.anchor.deviationM,
            "allowedDeviationM" to solution.anchor.allowedDeviationM,
            "freshness" to solution.anchor.freshness?.name,
            "freshnessReason" to solution.anchor.freshnessReason,
            "ageS" to solution.anchor.ageSeconds,
            "traveledM" to solution.anchor.traveledM,
            "sigmaM" to solution.anchor.propagatedSigmaM,
        ),
        // Наблюдения источников до сравнения (включая счисление) — для повторного прогона счисления и NEES.
        "observations" to observations.map {
            point(it.point) + mapOf("source" to it.source.name, "sigmaM" to it.accuracyM, "t" to it.timestampMillis)
        },
        "comparison" to mapOf(
            "accepted" to solution.comparison.accepted.map { it.source.name },
            "rejected" to solution.comparison.rejected.map { it.source.name },
        ),
        "gnss" to solution.gnss?.let { g ->
            linkedMapOf(
                "raim" to g.raim?.status?.name,
                "testStatistic" to g.raim?.testStatistic,
                "threshold" to g.raim?.threshold,
                "excluded" to g.raim?.excluded?.map { mapOf("sat" to it.sat.label, "residualM" to it.residualM) },
                "ephemerides" to g.ephemerisCount,
                "pseudoranges" to g.pseudorangeCount,
                "own" to g.ownSolution?.let { s -> point(s.point) + mapOf("clockBiasM" to s.clockBiasM) },
                "detectors" to g.detectors.map {
                    mapOf("kind" to it.kind.name, "verdict" to it.verdict.name, "detail" to it.detail)
                },
            )
        },
        "measurements" to measurements?.let {
            mapOf("count" to it.count, "bands" to it.bands.map { b -> b.name }, "agcDb" to it.agcDb)
        },
        "satellites" to mapOf("count" to satellites, "fromMeasurements" to satellitesFromMeasurements),
        "cells" to cells.map {
            linkedMapOf(
                "tech" to it.technology.name, "serving" to it.isServing, "mcc" to it.mcc, "mnc" to it.mnc,
                "area" to it.areaCode, "cid" to it.cellId, "pci" to it.pci, "dbm" to it.signalDbm, "ta" to it.timingAdvance, "age" to it.ageMillis,
            )
        },
        "sensors" to sensors.associate { it.kind.name to mapOf("values" to it.values, "t" to it.timeMillis) },
        // Датчики движения против ГНСС (с 0.8): шаги за 10 с, частота и ритм, покой, поворот гироскопа за 25…5 с.
        "motion" to motion?.let {
            mapOf(
                "steps10s" to it.steps, "cadenceHz" to it.cadenceHz, "stepCv" to it.stepIntervalCv,
                "walking" to it.walking, "stationaryS" to it.stationaryS, "yawDeg" to it.yawChangeDeg,
                "gnssSpeedMps" to gnssSpeedMps,
            )
        },
        "networkTime" to networkTime?.let {
            mapOf("offsetMs" to it.offsetMillis, "uncertaintyMs" to it.uncertaintyMillis, "method" to it.method)
        },
    )

    private fun point(p: GeoPoint): Map<String, Any?> = mapOf("lat" to p.latitude, "lon" to p.longitude, "alt" to p.altitude)
}
