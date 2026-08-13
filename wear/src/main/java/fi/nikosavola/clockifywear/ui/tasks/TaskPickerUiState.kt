package fi.nikosavola.clockifywear.ui.tasks

import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.api.dto.TaskDto

/** One state per screen render, no partial/combined states. */
sealed interface TaskPickerUiState {
  data object Loading : TaskPickerUiState

  data class Loaded(val projectName: String, val tasks: List<TaskDto>) : TaskPickerUiState

  /** Terminal: reached once a timer has actually started. The screen navigates back on this. */
  data object Started : TaskPickerUiState

  data class Error(val error: ClockifyError) : TaskPickerUiState
}
