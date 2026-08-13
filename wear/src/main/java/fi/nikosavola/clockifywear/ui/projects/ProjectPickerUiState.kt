package fi.nikosavola.clockifywear.ui.projects

import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto

/** One state per screen render, no partial/combined states. */
sealed interface ProjectPickerUiState {
  data object Loading : ProjectPickerUiState

  // An empty list is a valid Loaded state, rendered as an empty-state message in the Composable.
  data class Loaded(val projects: List<ProjectDto>) : ProjectPickerUiState

  data class Error(val error: ClockifyError) : ProjectPickerUiState
}
