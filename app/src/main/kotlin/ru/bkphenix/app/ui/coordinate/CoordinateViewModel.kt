package ru.bkphenix.app.ui.coordinate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.mapping.SourceTrust
import ru.bkphenix.app.data.mapping.TrustMapping
import ru.bkphenix.core.PhenixSolution

data class CoordinateUiState(
    val running: Boolean = false,
    val solution: PhenixSolution? = null,
    val trust: List<SourceTrust> = emptyList(),
)

class CoordinateViewModel(private val repository: PhenixRepository) : ViewModel() {
    val state: StateFlow<CoordinateUiState> = combine(repository.running, repository.solution) { running, solution ->
        CoordinateUiState(running, solution, solution?.let(TrustMapping::fromSolution).orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoordinateUiState())

    /** Якорь из текущей итоговой точки. @return false, если точки ещё нет. */
    fun anchorFromCurrent(): Boolean {
        val s = state.value.solution ?: return false
        // Якорь ставит человек: он доступен и когда компаратор всё отсеял — тогда берём
        // лучшее из имеющихся наблюдений (включая отсеянные), человек подтверждает место сам.
        val point = s.comparison.estimate
        if (point != null) {
            repository.setAnchor(point, s.comparison.accuracyM ?: AnchorInput.DEFAULT_ACCURACY_M)
            return true
        }
        val best = (s.comparison.accepted + s.comparison.rejected).minByOrNull { it.accuracyM } ?: return false
        repository.setAnchor(best.point, maxOf(best.accuracyM, AnchorInput.DEFAULT_ACCURACY_M))
        return true
    }

    fun anchorManual(latitude: String, longitude: String, accuracy: String): AnchorInputResult =
        AnchorInput.parse(latitude, longitude, accuracy).also {
            if (it is AnchorInputResult.Valid) repository.setAnchor(it.point, it.accuracyM)
        }

    fun clearAnchor() = repository.clearAnchor()
    fun confirmAnchorSuggestion() = repository.confirmAnchorSuggestion()
    fun dismissAnchorSuggestion() = repository.dismissAnchorSuggestion()
}
