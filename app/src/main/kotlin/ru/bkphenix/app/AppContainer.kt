package ru.bkphenix.app

import android.content.Context
import android.os.Build
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.map.MapFileStore
import ru.bkphenix.app.data.log.GnssLoggerFormat
import ru.bkphenix.app.data.log.RawLogRecorder
import ru.bkphenix.app.data.source.BleDataSource
import ru.bkphenix.app.data.source.CellDataSource
import ru.bkphenix.app.data.source.GnssDataSource
import ru.bkphenix.app.data.source.LocationDataSource
import ru.bkphenix.app.data.source.NetworkTimeDataSource
import ru.bkphenix.app.data.source.RinexEphemerisSource
import ru.bkphenix.app.data.source.SensorDataSource
import ru.bkphenix.app.data.source.WifiDataSource
import ru.bkphenix.app.data.track.TrackDatabase
import ru.bkphenix.app.data.track.TrackRecorder
import java.io.File

/** Ручное внедрение зависимостей: один репозиторий на процесс. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val repository: PhenixRepository by lazy {
        PhenixRepository(
            location = LocationDataSource(app),
            gnss = GnssDataSource(app),
            cells = CellDataSource(app),
            wifi = WifiDataSource(app),
            ble = BleDataSource(app),
            sensors = SensorDataSource(app),
            networkTimeSource = NetworkTimeDataSource(app),
            rinex = RinexEphemerisSource(File(app.filesDir, "ephemeris")),
            logRecorder = RawLogRecorder(File(app.filesDir, "logs")) {
                GnssLoggerFormat.header(
                    appVersion = BuildConfig.VERSION_NAME,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    platform = Build.VERSION.RELEASE,
                )
            },
        )
    }

    val trackRecorder: TrackRecorder by lazy {
        TrackRecorder(TrackDatabase.create(app).tracks(), repository.solution, File(app.filesDir, "gpx"))
    }

    val mapFiles: MapFileStore by lazy { MapFileStore(app) }
}
