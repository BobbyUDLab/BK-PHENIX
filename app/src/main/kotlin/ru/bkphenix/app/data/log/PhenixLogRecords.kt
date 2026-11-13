package ru.bkphenix.app.data.log

import ru.bkphenix.app.data.EpochDiagnostics
import ru.bkphenix.app.data.model.BleSnapshot
import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.app.data.model.RawLocation
import ru.bkphenix.app.data.model.WifiSnapshot
import ru.bkphenix.core.roads.MatchResult
import ru.bkphenix.core.terrain.VerticalCheck
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
        context: Map<String, Any?>? = null,
    ): Map<String, Any?> = linkedMapOf(
        "context" to context,
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
            // С 0.8.1: какой якорь (ручной/авто), где он и куда ушёл, предложение на остановке.
            "mode" to solution.anchor.anchor?.mode?.name,
            "anchorPoint" to solution.anchor.anchor?.let { point(it.point) + mapOf("sigmaM" to it.accuracyM, "t" to it.timestampMillis) },
            "propagated" to solution.anchor.propagatedPoint?.let { point(it) },
            "suggestion" to solution.anchor.suggestion?.let { point(it) },
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

    /**
     * Окружение эпохи (с 0.8): режим, запреты ГНСС, компас, счисление, позиция по вышкам, Wi-Fi/BLE, высота,
     * дорога и последний фикс чипа — всё, что нужно, чтобы по журналу восстановить решение без догадок.
     */
    fun context(
        d: EpochDiagnostics,
        vertical: VerticalCheck?,
        road: MatchResult?,
        wifi: List<WifiSnapshot>,
        ble: List<BleSnapshot>,
        gps: RawLocation?,
    ): Map<String, Any?> = linkedMapOf(
        "mode" to d.motionMode.name,
        "veto" to mapOf("altitude" to d.altitudeVeto, "motion" to d.motionVeto, "motionReasons" to d.motionReasons),
        "headingDeg" to d.compassHeadingDeg,
        "dr" to d.deadReckoning?.let {
            point(it.point) + mapOf(
                "sigmaM" to it.sigmaM, "speedMps" to it.speedMps, "bearingDeg" to it.bearingDeg,
                "sinceFixMs" to it.sinceFixMillis, "stationary" to it.stationary, "independent" to d.deadReckoningIndependent,
            )
        },
        "cellFix" to d.cellFix?.let { point(it.point) + mapOf("sigmaM" to it.accuracyM, "t" to it.timestampMillis) },
        "learnedCells" to d.learnedCells,
        "gpsFix" to gps?.let {
            mapOf(
                "lat" to it.latitude, "lon" to it.longitude, "alt" to it.altitude, "acc" to it.accuracy68M,
                "vacc" to it.verticalAccuracyM, "t" to it.timeMillis, "mock" to it.isMock,
            )
        },
        "vertical" to vertical?.let {
            mapOf(
                "gnssM" to it.gnssOrthometricM, "terrainM" to it.terrainM, "geoidM" to it.geoidM,
                "baroM" to it.barometric?.heightM, "baroSigmaM" to it.barometric?.sigmaM, "baroSource" to it.barometric?.source?.name,
                "refM" to it.referenceM, "refSigmaM" to it.referenceSigmaM, "residualM" to it.residualM, "thresholdM" to it.thresholdM,
            )
        },
        "road" to road?.let { mapOf("distanceM" to it.distanceToRoadM, "thresholdM" to it.offRoadThresholdM, "offRoad" to it.offRoad) },
        "wifi" to wifi.map { mapOf("bssid" to it.bssid, "rssi" to it.rssiDbm, "mhz" to it.frequencyMhz, "rttM" to it.rttDistanceM) },
        "ble" to ble.map { mapOf("addr" to it.address, "rssi" to it.rssiDbm, "tx" to it.txPowerDbm) },
    )

    private fun point(p: GeoPoint): Map<String, Any?> = mapOf("lat" to p.latitude, "lon" to p.longitude, "alt" to p.altitude)
}
