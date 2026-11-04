package ru.bkphenix.app.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.core.gnss.RinexNav
import ru.bkphenix.core.gnss.RinexNavParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Сведения о загруженном наборе эфемерид для UI. */
data class RinexEphemerisInfo(
    val satellites: Int,
    val records: Int,
    val fileTimeMillis: Long,
    val origin: String,
    /** Буквы систем в файле: «GREC J». */
    val systems: String = "",
)

/**
 * Запасные эфемериды GPS, ГЛОНАСС, Galileo, BeiDou и QZSS для чипов, которые не отдают навигационные
 * сообщения: суточный смешанный файл broadcast-эфемерид IGS с ионосферой Klobuchar (BKG, обновляется
 * каждые 15 мин) скачивается при наличии сети
 * и кэшируется в filesDir, чтобы собственный МНК и RAIM работали и без сети в течение суток.
 */
class RinexEphemerisSource(private val cacheDir: File) : DataSource("Эфемериды RINEX") {
    private val cacheFile = File(cacheDir, "brdc.rnx.gz")
    private val _info = MutableStateFlow<RinexEphemerisInfo?>(null)
    val info: StateFlow<RinexEphemerisInfo?> = _info.asStateFlow()

    /** Эфемериды из кэша, если он моложе [maxAgeMillis]. */
    suspend fun loadCached(nowMillis: Long, maxAgeMillis: Long = CACHE_MAX_AGE_MS): RinexNav? =
        withContext(Dispatchers.IO) {
            if (!cacheFile.exists() || nowMillis - cacheFile.lastModified() > maxAgeMillis) return@withContext null
            try {
                parse(cacheFile.readBytes(), cacheFile.lastModified(), "кэш")
            } catch (e: IOException) {
                Log.w(TAG, "Кэш эфемерид повреждён", e)
                null
            }
        }

    /** Скачивает свежий файл (сегодня по UTC, при отсутствии — вчера); `null` при неудаче. */
    suspend fun download(nowMillis: Long): RinexNav? = withContext(Dispatchers.IO) {
        for (url in RinexUrls.candidates(nowMillis)) {
            try {
                val bytes = fetch(url) ?: continue
                val list = parse(bytes, nowMillis, "сеть") ?: continue
                cacheDir.mkdirs()
                File(cacheDir, "brdc.tmp").apply { writeBytes(bytes) }.renameTo(cacheFile)
                return@withContext list
            } catch (e: IOException) {
                Log.w(TAG, "Не удалось скачать эфемериды: $url", e)
                setStatus(SourceStatus.Unavailable("нет сети или сервер недоступен"))
            }
        }
        null
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(gz: ByteArray, timeMillis: Long, origin: String): RinexNav? {
        val text = GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() }
        val nav = RinexNavParser.parse(text).takeIf { it.ephemerides.isNotEmpty() } ?: return null
        val sats = nav.ephemerides.map { it.sat }.distinct()
        setStatus(SourceStatus.Active)
        _info.value = RinexEphemerisInfo(
            sats.size, nav.ephemerides.size, timeMillis, origin,
            sats.map { it.label.first() }.distinct().joinToString(""),
        )
        return nav
    }

    companion object {
        const val CACHE_MAX_AGE_MS: Long = 24 * 3600_000L
        private const val TIMEOUT_MS = 20_000
    }
}

/**
 * Адреса суточных смешанных файлов RINEX 3 на сервере BKG: сначала BRDM00DLR_S (DLR, все системы и
 * коэффициенты ионосферы GPSA/GPSB), запасной — BRDC00WRD_S (без заголовка ионосферы).
 */
object RinexUrls {
    private const val BASE = "https://igs.bkg.bund.de/root_ftp/IGS/BRDC"

    fun forDay(year: Int, dayOfYear: Int): String {
        val doy = String.format(Locale.ROOT, "%03d", dayOfYear)
        return "$BASE/$year/$doy/BRDM00DLR_S_$year${doy}0000_01D_MN.rnx.gz"
    }

    fun fallbackForDay(year: Int, dayOfYear: Int): String {
        val doy = String.format(Locale.ROOT, "%03d", dayOfYear)
        return "$BASE/$year/$doy/BRDC00WRD_S_$year${doy}0000_01D_MN.rnx.gz"
    }

    /** Сегодня и вчера по UTC: текущий файл появляется через несколько минут после полуночи. */
    fun candidates(nowMillis: Long): List<String> {
        val today = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC).toLocalDate()
        return listOf(today, today.minusDays(1)).flatMap {
            listOf(forDay(it.year, it.dayOfYear), fallbackForDay(it.year, it.dayOfYear))
        }
    }
}
