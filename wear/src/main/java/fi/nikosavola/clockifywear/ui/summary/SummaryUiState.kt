package fi.nikosavola.clockifywear.ui.summary

import androidx.compose.ui.graphics.Color
import fi.nikosavola.clockifywear.data.ClockifyError

/** One entry in a Summary section, sorted most-recent-start-first. */
data class SummaryEntryDisplay(
  val id: String,
  // Null when the entry has no project, or its project id doesn't resolve against the cached
  // list; the screen substitutes a localized "No project" label, same as the Tile's fallback.
  val projectName: String?,
  // Null for an unresolvable project or a malformed/missing color, same tolerant fallback as
  // RecentEntryDisplay.
  val projectColor: Color?,
  val description: String?,
  val durationSeconds: Long,
)

/** A grouped period (Today, This week, or Last week) with its total duration. */
data class SummarySection(val totalSeconds: Long, val entries: List<SummaryEntryDisplay>)

/** One state per screen render, no partial/combined states. */
sealed interface SummaryUiState {
  data object Loading : SummaryUiState

  data class Loaded(
    val today: SummarySection,
    val thisWeek: SummarySection,
    val lastWeek: SummarySection,
  ) : SummaryUiState

  data class Error(val error: ClockifyError) : SummaryUiState
}
