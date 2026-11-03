package ru.bkphenix.core.model

/** Физическая природа источника. */
public enum class SourceCategory { SATELLITE, RADIO, INERTIAL, TERRAIN }

/**
 * Источник наблюдения координаты. Каждый источник попадает в компаратор как независимый,
 * со своей погрешностью. Ни один источник, кроме подтверждённого якоря, не считается абсолютным.
 */
public enum class PositionSource(public val category: SourceCategory, public val displayName: String) {
    /** Решение ГНСС-приёмника (GPS, ГЛОНАСС, Galileo, BeiDou, QZSS/SBAS) через AOSP LocationManager. */
    GNSS(SourceCategory.SATELLITE, "ГНСС"),

    /** Позиция сети (AOSP network provider), без GMS. */
    NETWORK(SourceCategory.RADIO, "Сеть"),

    /** Триангуляция/мультилатерация по вышкам LTE/NR/GSM/WCDMA (RSRP, Timing Advance, PCI) и офлайн-базе вышек. */
    CELL(SourceCategory.RADIO, "Сотовые вышки"),

    /** Wi-Fi по уровню сигнала (RSSI). */
    WIFI_RSSI(SourceCategory.RADIO, "Wi-Fi (RSSI)"),

    /** Wi-Fi RTT (IEEE 802.11mc), измерение дальности по времени прохождения. */
    WIFI_RTT(SourceCategory.RADIO, "Wi-Fi RTT"),

    /** Bluetooth/BLE-маяки. */
    BLUETOOTH(SourceCategory.RADIO, "Bluetooth/BLE"),

    /** Счисление пути по инерциальным датчикам. */
    DEAD_RECKONING(SourceCategory.INERTIAL, "Счисление пути"),

    /** Барометрическая высота против цифровой модели рельефа (SRTM). */
    TERRAIN_ELEVATION(SourceCategory.TERRAIN, "Рельеф (SRTM)"),

    /** Сопоставление с картой магнитных аномалий. */
    MAGNETIC_ANOMALY(SourceCategory.TERRAIN, "Магнитные аномалии"),

    /** Привязка к дорогам OSM (map matching). */
    MAP_MATCHING(SourceCategory.TERRAIN, "Дороги OSM"),

    /** Известный ориентир, отмеченный пользователем или из базы. */
    LANDMARK(SourceCategory.TERRAIN, "Ориентир"),
    ;

    public val isSatellite: Boolean get() = category == SourceCategory.SATELLITE
}
