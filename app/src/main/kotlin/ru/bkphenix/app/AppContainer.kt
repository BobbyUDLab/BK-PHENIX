package ru.bkphenix.app

import android.content.Context
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.source.BleDataSource
import ru.bkphenix.app.data.source.CellDataSource
import ru.bkphenix.app.data.source.GnssDataSource
import ru.bkphenix.app.data.source.LocationDataSource
import ru.bkphenix.app.data.source.NetworkTimeDataSource
import ru.bkphenix.app.data.source.SensorDataSource
import ru.bkphenix.app.data.source.WifiDataSource

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
        )
    }
}
