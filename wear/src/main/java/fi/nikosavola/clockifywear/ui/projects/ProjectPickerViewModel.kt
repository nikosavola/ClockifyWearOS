package fi.nikosavola.clockifywear.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @param repository the data source for the project list.
 * @param settingsStore read for the current default project id, to sort it to the top.
 * @param settingsPrimed awaited before the first repository call, same discipline as
 *   [fi.nikosavola.clockifywear.ui.timer.TimerViewModel]; see
 *   [fi.nikosavola.clockifywear.di.AppContainer] for why skipping this can 401 a cold start.
 */
class ProjectPickerViewModel(
  private val repository: ClockifyRepository,
  private val settingsStore: SettingsStore,
  private val settingsPrimed: Deferred<Settings> = CompletableDeferred(Settings()),
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<ProjectPickerUiState>(ProjectPickerUiState.Loading)
  val uiState: StateFlow<ProjectPickerUiState> = mutableUiState.asStateFlow()

  /** Returns the launched [Job] so tests can `join()` a real MockWebServer round trip. */
  fun load(): Job = viewModelScope.launch {
    settingsPrimed.await()
    when (val result = repository.projects()) {
      is ClockifyResult.Failure -> {
        mutableUiState.value = ProjectPickerUiState.Error(result.error)
      }
      is ClockifyResult.Success -> {
        val defaultProjectId = settingsStore.currentSettings().defaultProjectId
        mutableUiState.value = ProjectPickerUiState.Loaded(sorted(result.value, defaultProjectId))
      }
    }
  }

  private fun sorted(projects: List<ProjectDto>, defaultProjectId: String?): List<ProjectDto> =
    projects.sortedWith(compareBy({ it.id != defaultProjectId }, { it.name.lowercase() }))
}
