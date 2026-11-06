package ru.bkphenix.app.ui.map

import ru.bkphenix.app.ui.theme.PhenixPalette
import android.graphics.Color
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.geo.ErrorCircle
import ru.bkphenix.core.integrity.PointTrust
import ru.bkphenix.core.integrity.PointTrustLevel
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.track.HeldPosition
import ru.bkphenix.core.track.TrackPoint
import ru.bkphenix.core.track.TrackSegments
import ru.bkphenix.core.roads.MatchResult

/** Слои приложения поверх базовой карты: трек, источники, якорь, позиция с кругом ошибки. */
internal object MapLayers {
    private const val TRACK = "bk-track"
    private const val SOURCES = "bk-sources"
    private const val ANCHOR = "bk-anchor"
    private const val POSITION = "bk-position"
    private const val ROAD = "bk-road"

    /** Цвета слоёв приложения; на тёмной карте — циан и оранжевый бренда, обводки чёрные. */
    private var dark = false

    fun install(style: Style, oled: Boolean = false) {
        dark = oled
        listOf(TRACK, SOURCES, ANCHOR, ROAD, POSITION).forEach { style.addSource(GeoJsonSource(it)) }
        // Трек — отрезками: сплошная линия — обычные точки, пунктир — грубые (сеть, счисление, якорь при подмене).
        val trackColor = if (oled) PhenixPalette.hex(PhenixPalette.ORANGE) else "#E0457B"
        style.addLayer(
            LineLayer(TRACK, TRACK).withFilter(Expression.eq(Expression.get("coarse"), false)).withProperties(
                PropertyFactory.lineColor(trackColor),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer("$TRACK-coarse", TRACK).withFilter(Expression.eq(Expression.get("coarse"), true)).withProperties(
                PropertyFactory.lineColor(trackColor),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineOpacity(0.6f),
                PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // Привязка к дороге: тонкая линия от исходной точки к точке на дороге и маленький ромб-кружок.
        // Исходная точка остаётся главным маркером — привязка её не заменяет.
        style.addLayer(
            LineLayer("$ROAD-link", ROAD).withFilter(Expression.eq(Expression.geometryType(), "LineString")).withProperties(
                PropertyFactory.lineColor(ROAD_COLOR),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineDasharray(arrayOf(1f, 1.5f)),
            ),
        )
        style.addLayer(
            CircleLayer("$ROAD-dot", ROAD).withFilter(Expression.eq(Expression.geometryType(), "Point")).withProperties(
                PropertyFactory.circleColor(ROAD_COLOR),
                PropertyFactory.circleRadius(4.5f),
                PropertyFactory.circleStrokeColor(if (oled) Color.BLACK else Color.WHITE),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        // Круги ошибки (полигоны в метрах) и центры точек — отдельными слоями по типу геометрии.
        for (id in listOf(SOURCES, ANCHOR, POSITION)) {
            style.addLayer(
                FillLayer("$id-area", id).withFilter(Expression.eq(Expression.geometryType(), "Polygon")).withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.fillOpacity(Expression.toNumber(Expression.get("opacity"))),
                    PropertyFactory.fillOutlineColor(Expression.toColor(Expression.get("color"))),
                ),
            )
            style.addLayer(
                CircleLayer("$id-dot", id).withFilter(Expression.eq(Expression.geometryType(), "Point")).withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(Expression.toNumber(Expression.get("radius"))),
                    PropertyFactory.circleStrokeColor(if (oled) Color.BLACK else Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
        }
        // HPL — пунктирная окружность вокруг позиции, отдельно от круга ошибки 95 %.
        style.addLayer(
            LineLayer("$POSITION-hpl", POSITION).withFilter(Expression.eq(Expression.geometryType(), "LineString")).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineOpacity(0.9f),
            ),
        )
        style.addLayer(
            SymbolLayer("$SOURCES-label", SOURCES).withFilter(Expression.eq(Expression.geometryType(), "Point")).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                PropertyFactory.textColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.textHaloColor(if (oled) Color.BLACK else Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(true),
            ),
        )
    }

    fun update(style: Style, solution: PhenixSolution?, held: HeldPosition?, track: List<TrackPoint>, road: MatchResult? = null) {
        style.getSourceAs<GeoJsonSource>(ROAD)?.setGeoJson(
            FeatureCollection.fromFeatures(
                road?.matched?.let { m -> listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(road.raw.toPoint(), m.toPoint()))), Feature.fromGeometry(m.toPoint())) }.orEmpty(),
            ),
        )
        style.getSourceAs<GeoJsonSource>(TRACK)?.setGeoJson(
            FeatureCollection.fromFeatures(
                TrackSegments.split(track).map { seg ->
                    Feature.fromGeometry(LineString.fromLngLats(seg.points.map { it.point.toPoint() })).apply {
                        addBooleanProperty("coarse", seg.coarse)
                    }
                },
            ),
        )
        val sources = mutableListOf<Feature>()
        solution?.comparison?.let { c ->
            c.accepted.forEach { sources += marker(it.point, it.accuracyM, it.source.color(), it.source.displayName, 0.08) }
            c.rejected.forEach { sources += marker(it.point, it.accuracyM, REJECTED, "${it.source.displayName} ✕", 0.05) }
        }
        style.getSourceAs<GeoJsonSource>(SOURCES)?.setGeoJson(FeatureCollection.fromFeatures(sources))
        val anchor = solution?.anchor?.anchor
        style.getSourceAs<GeoJsonSource>(ANCHOR)?.setGeoJson(
            FeatureCollection.fromFeatures(anchor?.let { marker(it.point, it.accuracyM, ANCHOR_COLOR, null, 0.12, 8.0) }.orEmpty()),
        )
        style.getSourceAs<GeoJsonSource>(POSITION)?.setGeoJson(
            FeatureCollection.fromFeatures(
                held?.let {
                    // Точка без кворума рисуется цветом «не подтверждено», а не цветом надёжной позиции.
                    val trust = PointTrust.of(solution).level
                    val color = when {
                        it.extrapolated -> HELD_COLOR
                        trust == PointTrustLevel.ALARM -> REJECTED
                        trust != PointTrustLevel.RELIABLE -> UNCONFIRMED_COLOR
                        else -> POSITION_COLOR
                    }
                    val hpl = solution?.gnss?.raim?.horizontalProtectionLevelM?.takeIf { !held.extrapolated }
                    marker(it.point, it.accuracyM, color, null, 0.2, 9.0) + listOfNotNull(hpl?.let { r -> ring(it.point, r, color) })
                }.orEmpty(),
            ),
        )
    }

    private fun marker(p: GeoPoint, accuracyM: Double, color: String, label: String?, opacity: Double, radius: Double = 6.0): List<Feature> {
        val dot = Feature.fromGeometry(p.toPoint()).apply {
            addStringProperty("color", color)
            addNumberProperty("radius", radius)
            label?.let { addStringProperty("label", it) }
        }
        val r95 = ErrorCircle.radius(accuracyM)
        val ring = (0..CIRCLE_STEPS).map { i -> p.offset(r95, 360.0 * i / CIRCLE_STEPS).toPoint() }
        val area = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
            addStringProperty("color", color)
            addNumberProperty("opacity", opacity)
        }
        return listOf(area, dot)
    }

    private fun ring(p: GeoPoint, radiusM: Double, color: String): Feature =
        Feature.fromGeometry(LineString.fromLngLats((0..CIRCLE_STEPS).map { i -> p.offset(radiusM, 360.0 * i / CIRCLE_STEPS).toPoint() })).apply {
            addStringProperty("color", color)
        }

    private fun GeoPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

    private const val CIRCLE_STEPS = 48
    private val POSITION_COLOR get() = if (dark) PhenixPalette.hex(PhenixPalette.CYAN) else "#1A73E8"
    private val ROAD_COLOR get() = if (dark) PhenixPalette.hex(PhenixPalette.GOLD) else "#6D4C41"
    private val HELD_COLOR get() = if (dark) "#A9B8CC" else "#8A8F98"
    /** Янтарный: точка без кворума независимых источников. */
    private val UNCONFIRMED_COLOR get() = if (dark) "#FFB300" else "#E37400"
    private val ANCHOR_COLOR get() = if (dark) "#C792EA" else "#7B1FA2"
    private val REJECTED get() = if (dark) PhenixPalette.hex(PhenixPalette.Oled.STATUS_BAD) else "#D93A3A"

    private fun PositionSource.color(): String = if (dark) {
        when (this) {
            PositionSource.GNSS, PositionSource.GNSS_WLS -> PhenixPalette.hex(PhenixPalette.Oled.STATUS_OK)
            PositionSource.NETWORK -> PhenixPalette.hex(PhenixPalette.GOLD)
            PositionSource.CELL -> "#FF7AB6"
            PositionSource.WIFI_RSSI, PositionSource.WIFI_RTT -> "#4DD8E6"
            PositionSource.BLUETOOTH -> "#8C9EFF"
            else -> "#D7B899"
        }
    } else {
        when (this) {
            PositionSource.GNSS -> "#2E9D5B"
            PositionSource.NETWORK -> "#E08A00"
            PositionSource.CELL -> "#C2185B"
            PositionSource.WIFI_RSSI, PositionSource.WIFI_RTT -> "#00838F"
            PositionSource.BLUETOOTH -> "#3949AB"
            else -> "#5D4037"
        }
    }
}
