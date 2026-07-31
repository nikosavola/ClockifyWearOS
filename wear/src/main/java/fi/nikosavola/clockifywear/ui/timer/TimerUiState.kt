package fi.nikosavola.clockifywear.ui.timer

import fi.nikosavola.clockifywear.data.ClockifyError
import java.time.Instant

/** Per PLANNING.md "State management": one state per screen render, no partial/combined states. */
sealed interface TimerUiState {
  data object Loading : TimerUiState

  data class Idle(val hasDefaultProject: Boolean) : TimerUiState

  data class Running(val projectId: String?, val startInstant: Instant, val elapsedSeconds: Long) :
    TimerUiState

  data class Error(val error: ClockifyError) : TimerUiState
}
