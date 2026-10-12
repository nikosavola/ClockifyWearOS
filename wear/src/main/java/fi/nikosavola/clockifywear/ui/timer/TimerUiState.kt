package fi.nikosavola.clockifywear.ui.timer

import androidx.compose.ui.graphics.Color
import fi.nikosavola.clockifywear.data.ClockifyError
import java.time.Instant

/**
 * One state per screen render, no partial/combined states.
 *
 * Deliberately nothing here changes once a second: the elapsed-time readout is exposed separately
 * by [TimerViewModel.elapsedSeconds], since a value inside this state would invalidate every reader
 * of it (see that property).
 */
sealed interface TimerUiState {
  data class Idle(val hasDefaultProject: Boolean) : TimerUiState

  data class Running(
    val projectId: String?,
    val projectName: String?,
    val projectColor: Color?,
    val startInstant: Instant,
    val description: String?,
  ) : TimerUiState

  data class Error(val error: ClockifyError) : TimerUiState
}
