package fi.nikosavola.clockifywear.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.dto.TaskDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @param repository the data source for tasks and starting the timer.
 * @param settingsStore persists the chosen project/task as the new default on a successful start.
 * @param projectId the project this picker lists tasks for.
 * @param settingsPrimed awaited before the first repository call, same discipline as
 *   [fi.nikosavola.clockifywear.ui.timer.TimerViewModel]; see
 *   [fi.nikosavola.clockifywear.di.AppContainer] for why skipping this can 401 a cold start.
 */
class TaskPickerViewModel(
  private val repository: ClockifyRepository,
  private val settingsStore: SettingsStore,
  private val projectId: String,
  private val settingsPrimed: Deferred<Settings> = CompletableDeferred(Settings()),
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<TaskPickerUiState>(TaskPickerUiState.Loading)
  val uiState: StateFlow<TaskPickerUiState> = mutableUiState.asStateFlow()

  /** Returns the launched [Job] so tests can `join()` a real MockWebServer round trip. */
  fun load(): Job = viewModelScope.launch {
    settingsPrimed.await()
    when (val tasksResult = repository.tasks(projectId)) {
      is ClockifyResult.Failure -> {
        mutableUiState.value = TaskPickerUiState.Error(tasksResult.error)
      }
      is ClockifyResult.Success -> {
        onTasksLoaded(tasksResult.value)
      }
    }
  }

  /** Reached both from [load] (a project with no tasks auto-starts) and here. */
  fun selectTask(taskId: String?): Job = viewModelScope.launch {
    settingsPrimed.await()
    when (val result = repository.startTimer(projectId = projectId, taskId = taskId)) {
      is ClockifyResult.Success -> {
        settingsStore.setDefaultProjectId(projectId)
        // Explicitly picking "no specific task" must overwrite a stale default, not skip the write.
        settingsStore.setDefaultTaskId(taskId)
        mutableUiState.value = TaskPickerUiState.Started
      }
      is ClockifyResult.Failure -> {
        mutableUiState.value = TaskPickerUiState.Error(result.error)
      }
    }
  }

  private suspend fun onTasksLoaded(tasks: List<TaskDto>) {
    if (tasks.isEmpty()) {
      startWithoutTask()
    } else {
      mutableUiState.value = TaskPickerUiState.Loaded(resolveProjectName(), tasks)
    }
  }

  private suspend fun startWithoutTask() {
    when (val result = repository.startTimer(projectId = projectId)) {
      is ClockifyResult.Success -> {
        settingsStore.setDefaultProjectId(projectId)
        settingsStore.setDefaultTaskId(null)
        mutableUiState.value = TaskPickerUiState.Started
      }
      is ClockifyResult.Failure -> {
        mutableUiState.value = TaskPickerUiState.Error(result.error)
      }
    }
  }

  // The project name is cosmetic (only shown in the header); a failed lookup must never turn an
  // otherwise-successful task load into an error state, so it falls back to the raw id.
  private suspend fun resolveProjectName(): String =
    when (val projectsResult = repository.projects()) {
      is ClockifyResult.Success -> {
        projectsResult.value.firstOrNull { it.id == projectId }?.name ?: projectId
      }
      is ClockifyResult.Failure -> {
        projectId
      }
    }
}
