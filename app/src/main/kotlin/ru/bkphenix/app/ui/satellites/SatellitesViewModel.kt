package ru.bkphenix.app.ui.satellites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.bkphenix.app.data.NavigationStats
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.model.GnssCapabilitiesSnapshot
import ru.bkphenix.app.data.model.MeasurementSummary
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.core.model.SatelliteStatus

data class SatellitesUiState(
    val satellites: List<SatelliteStatus> = emptyList(),
    val summary: List<ConstellationSummary> = emptyList(),
    val dualFrequency: Boolean? = null,
    val fromMeasurements: Boolean = false,
    val statusCallbacks: Long = 0,
    val capabilities: GnssCapabilitiesSnapshot? = null,
    val measurements: MeasurementSummary? = null,
    val measurementsStatus: SourceStatus = SourceStatus.Idle,
    val navigation: NavigationStats = NavigationStats(),
    val navigationStatus: SourceStatus = SourceStatus.Idle,
    val raim: RaimUi? = null,
)

class SatellitesViewModel(repository: PhenixRepository) : ViewModel() {
    private val statuses = repository.statusFlows

    val state: StateFlow<SatellitesUiState> = combine(
        repository.satellites,
        repository.capabilities,
        repository.measurements,
        repository.navigation,
        combine(statuses.getValue("Сырые измерения"), statuses.getValue("Навигационные сообщения")) { a, b -> a to b },
    ) { sats, caps, meas, nav, (measStatus, navStatus) ->
        SatellitesUiState(
            satellites = sats.sortedWith(compareBy({ it.constellation.ordinal }, { it.svid })),
            summary = SatelliteSummary.byConstellation(sats),
            dualFrequency = SatelliteSummary.isDualFrequency(sats, meas?.bands),
            capabilities = caps,
            measurements = meas,
            measurementsStatus = measStatus,
            navigation = nav,
            navigationStatus = navStatus,
        )
    }.combine(repository.solution) { ui, solution ->
        ui.copy(raim = RaimPresentation.from(solution?.gnss))
    }.combine(
        combine(repository.satellitesFromMeasurements, repository.satelliteStatusCallbacks) { a, b -> a to b },
    ) { ui, (fromMeasurements, callbacks) ->
        ui.copy(fromMeasurements = fromMeasurements, statusCallbacks = callbacks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SatellitesUiState())
}
