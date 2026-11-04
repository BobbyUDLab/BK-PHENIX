package ru.bkphenix.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.bkphenix.app.PhenixApplication
import ru.bkphenix.app.ui.coordinate.CoordinateViewModel
import ru.bkphenix.app.ui.diagnostics.DiagnosticsViewModel
import ru.bkphenix.app.ui.map.MapViewModel
import ru.bkphenix.app.ui.satellites.SatellitesViewModel
import ru.bkphenix.app.ui.track.TrackViewModel

object ViewModelFactory {
    val instance: ViewModelProvider.Factory = viewModelFactory {
        initializer { CoordinateViewModel(repository()) }
        initializer { SatellitesViewModel(repository()) }
        initializer { DiagnosticsViewModel(repository()) }
        initializer { TrackViewModel(container().trackRecorder) }
        initializer { container().let { MapViewModel(it.repository, it.trackRecorder, it.mapFiles, it.roadGraphs, it.terrainFiles) } }
    }

    private fun androidx.lifecycle.viewmodel.CreationExtras.container() =
        (this[APPLICATION_KEY] as PhenixApplication).container

    private fun androidx.lifecycle.viewmodel.CreationExtras.repository() = container().repository
}
