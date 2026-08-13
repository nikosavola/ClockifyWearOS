package fi.nikosavola.clockifywear.ui.recents

import androidx.compose.ui.graphics.Color
import fi.nikosavola.clockifywear.data.ClockifyError

/** An entry displayable for one-tap restart; only entries with a non-null projectId qualify. */
data class RecentEntryDisplay(
  val projectId: String,
  val taskId: String?,
  val description: String?,
  val projectName: String,
  // Null for an unresolvable project or a malformed/missing color, same tolerant fallback as
  // projectName; never lets a cosmetic color lookup fail the whole load.
  val projectColor: Color? = null,
)

/** One state per screen render, no partial/combined states. */
sealed interface RecentsUiState {
  data object Loading : RecentsUiState

  // An empty list is a valid Loaded state, rendered as an empty-state message in the Composable.
  data class Loaded(val entries: List<RecentEntryDisplay>) : RecentsUiState

  /** Terminal: reached once a timer has actually started. The screen navigates back on this. */
  data object Started : RecentsUiState

  data class Error(val error: ClockifyError) : RecentsUiState
}
