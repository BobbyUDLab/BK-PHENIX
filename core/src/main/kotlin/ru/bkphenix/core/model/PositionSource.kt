package ru.bkphenix.core.model

/** Физическая природа источника. */
public enum class SourceCategory { SATELLITE, RADIO, INERTIAL, TERRAIN }

/**
 * Группа зависимости: общий физический сигнал или общий «свидетель». Источники одной группы
 * ошибаются вместе (подмена GNSS уводит и чип, и наш WLS, и Fused), поэтому дают один голос.
 */
public enum class SourceGroup(public val displayName: String) {
    GNSS_SIGNAL("GNSS-сигнал"),
    CELLULAR("Сотовая сеть"),
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    INERTIAL("Датчики/счисление"),
    MAP("Карта"),
    HUMAN("Человек"),
}

/**
 * Источник наблюдения координаты. Каждый источник попадает в компаратор как независимый,
 * со своей погрешностью. Ни один источник, кроме подтверждённого якоря, не считается абсолютным.
 */
public enum class PositionSource(
    public val category: SourceCategory,
    public val displayName: String,
    /** Происхождение по умолчанию: на каких сигналах построена позиция. Наблюдение может его уточнить. */
    public val defaultOrigin: Set<SourceGroup>,
) {
    /** Решение ГНСС-приёмника (GPS, ГЛОНАСС, Galileo, BeiDou, QZSS/SBAS) через AOSP LocationManager. */
    GNSS(SourceCategory.SATELLITE, "ГНСС", setOf(SourceGroup.GNSS_SIGNAL)),

    /** Собственное решение WLS по сырым псевдодальностям: те же сигналы, что у чипа. */
    GNSS_WLS(SourceCategory.SATELLITE, "ГНСС (WLS)", setOf(SourceGroup.GNSS_SIGNAL)),

    /** Позиция сети (AOSP network provider), без GMS. */
    NETWORK(SourceCategory.RADIO, "Сеть", setOf(SourceGroup.CELLULAR, SourceGroup.WIFI)),

    /** Fused-позиция платформы: смешивает GNSS и сеть. */
    FUSED(SourceCategory.SATELLITE, "Fused", setOf(SourceGroup.GNSS_SIGNAL, SourceGroup.CELLULAR, SourceGroup.WIFI)),

    /** Триангуляция/мультилатерация по вышкам LTE/NR/GSM/WCDMA (RSRP, Timing Advance, PCI) и офлайн-базе вышек. */
    CELL(SourceCategory.RADIO, "Сотовые вышки", setOf(SourceGroup.CELLULAR)),

    /** Wi-Fi по уровню сигнала (RSSI). */
    WIFI_RSSI(SourceCategory.RADIO, "Wi-Fi (RSSI)", setOf(SourceGroup.WIFI)),

    /** Wi-Fi RTT (IEEE 802.11mc), измерение дальности по времени прохождения. */
    WIFI_RTT(SourceCategory.RADIO, "Wi-Fi RTT", setOf(SourceGroup.WIFI)),

    /** Bluetooth/BLE-маяки. */
    BLUETOOTH(SourceCategory.RADIO, "Bluetooth/BLE", setOf(SourceGroup.BLUETOOTH)),

    /** Счисление пути по инерциальным датчикам. */
    DEAD_RECKONING(SourceCategory.INERTIAL, "Счисление пути", setOf(SourceGroup.INERTIAL)),

    /** Барометрическая высота против цифровой модели рельефа (SRTM). */
    TERRAIN_ELEVATION(SourceCategory.TERRAIN, "Рельеф (SRTM)", setOf(SourceGroup.INERTIAL)),

    /** Сопоставление с картой магнитных аномалий. */
    MAGNETIC_ANOMALY(SourceCategory.TERRAIN, "Магнитные аномалии", setOf(SourceGroup.INERTIAL)),

    /** Привязка к дорогам OSM (map matching). */
    MAP_MATCHING(SourceCategory.TERRAIN, "Дороги OSM", setOf(SourceGroup.MAP)),

    /** Известный ориентир, отмеченный пользователем или из базы. */
    LANDMARK(SourceCategory.TERRAIN, "Ориентир", setOf(SourceGroup.HUMAN)),
    ;

    public val isSatellite: Boolean get() = category == SourceCategory.SATELLITE

    /**
     * Источник может тайно опираться на GNSS (сетевой провайдер некоторых прошивок, например Huawei,
     * подмешивает спутниковую позицию). Такой источник получает пониженный вес и не даёт кворума.
     */
    public val mayUseGnss: Boolean get() = this == NETWORK
}
