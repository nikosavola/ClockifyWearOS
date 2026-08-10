package fi.nikosavola.clockifywear.ui.recents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @param repository the data source for recent entries and starting the timer.
 * @param settingsStore persists the restarted entry's project/task as the new default, same
 *   convention as [fi.nikosavola.clockifywear.ui.tasks.TaskPickerViewModel.selectTask].
 * @param settingsPrimed awaited before the first repository call, same discipline as
 *   [fi.nikosavola.clockifywear.ui.timer.TimerViewModel]; see
 *   [fi.nikosavola.clockifywear.di.AppContainer] for why skipping this can 401 a cold start.
 */
class RecentsViewModel(
  private val repository: ClockifyRepository,
  private val settingsStore: SettingsStore,
  private val settingsPrimed: Deferred<Settings> = CompletableDeferred(Settings()),
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<RecentsUiState>(RecentsUiState.Loading)
  val uiState: StateFlow<RecentsUiState> = mutableUiState.asStateFlow()

  /** Returns the launched [Job] so tests can `join()` a real MockWebServer round trip. */
  fun load(): Job = viewModelScope.launch {
    settingsPrimed.await()
    when (val result = repository.recentEntries()) {
      is ClockifyResult.Failure -> {
        mutableUiState.value = RecentsUiState.Error(result.error)
      }
      is ClockifyResult.Success -> {
        mutableUiState.value = RecentsUiState.Loaded(displayEntries(result.value))
      }
    }
  }

  fun restart(entry: RecentEntryDisplay): Job = viewModelScope.launch {
    settingsPrimed.await()
    when (
      val result =
        repository.startTimer(
          projectId = entry.projectId,
          taskId = entry.taskId,
          description = entry.description,
        )
    ) {
      is ClockifyResult.Success -> {
        settingsStore.setDefaultProjectId(entry.projectId)
        settingsStore.setDefaultTaskId(entry.taskId)
        mutableUiState.value = RecentsUiState.Started
      }
      is ClockifyResult.Failure -> {
        mutableUiState.value = RecentsUiState.Error(result.error)
      }
    }
  }

  // A projectless entry can't be restarted (startTimer requires a non-null projectId), so it is
  // dropped before the projects() lookup rather than after: that is the whole reason an empty
  // filtered list skips the lookup entirely instead of calling it once per entry.
  private suspend fun displayEntries(entries: List<TimeEntryDto>): List<RecentEntryDisplay> {
    val restartable = entries.filter { it.projectId != null }
    if (restartable.isEmpty()) return emptyList()
    val projects = resolveProjects()
    return restartable.map { entry ->
      val projectId = requireNotNull(entry.projectId)
      RecentEntryDisplay(
        projectId = projectId,
        taskId = entry.taskId,
        description = entry.description,
        projectName = projects.firstOrNull { it.id == projectId }?.name ?: projectId,
      )
    }
  }

  // Cosmetic-only lookup: a failed call must never turn an otherwise-successful recents fetch into
  // an error state, same tolerance as TaskPickerViewModel.resolveProjectName. An empty result here
  // falls back to the raw id per entry above.
  private suspend fun resolveProjects(): List<ProjectDto> =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value
      is ClockifyResult.Failure -> emptyList()
    }
}
