package fi.nikosavola.clockifywear.ui.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.ui.projects.parseProjectColor
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TICK_INTERVAL_MS = 1_000L

/**
 * @param repository the data source for running/start/stop operations.
 * @param settingsStore read directly (not only through [repository]) for the default project id and
 *   to prime identity ahead of a request; see [settingsPrimed].
 * @param settingsPrimed awaited before the first repository call; see
 *   [fi.nikosavola.clockifywear.di.AppContainer] for why. Defaults to an already-completed
 *   [Deferred] so tests that don't care about priming don't need to construct one.
 * @param clock injectable so elapsed-time tests use virtual instants, not the wall clock.
 */
class TimerViewModel(
  private val repository: ClockifyRepository,
  private val settingsStore: SettingsStore,
  private val settingsPrimed: Deferred<Settings> = CompletableDeferred(Settings()),
  private val clock: () -> Instant = Instant::now,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<TimerUiState>(TimerUiState.Loading)
  val uiState: StateFlow<TimerUiState> = mutableUiState.asStateFlow()

  // Separate from uiState: "a refresh is in flight" is orthogonal to which state is currently
  // showing (Idle/Running/Error all keep rendering while a background refresh reloads them).
  private val mutableIsRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = mutableIsRefreshing.asStateFlow()

  /**
   * Called from the screen's lifecycle-scoped collection site on every foreground. Returns the
   * launched [Job] so tests can `join()` it: a real suspension, unlike
   * `advanceUntilIdle()`/`advanceTimeBy()`, correctly waits out the real MockWebServer round trip
   * these repository calls make, which virtual-time advancement alone does not.
   */
  fun onForeground(): Job = viewModelScope.launch { loadRunning() }

  fun retry(): Job = onForeground()

  fun start(): Job = viewModelScope.launch {
    settingsPrimed.await()
    val settings = settingsStore.currentSettings()
    val defaultProjectId = settings.defaultProjectId
    if (defaultProjectId != null) {
      when (
        val result =
          repository.startTimer(projectId = defaultProjectId, taskId = settings.defaultTaskId)
      ) {
        is ClockifyResult.Success -> applyEntry(result.value)
        is ClockifyResult.Failure -> mutableUiState.value = TimerUiState.Error(result.error)
      }
    } else {
      mutableUiState.value = TimerUiState.Idle(hasDefaultProject = false)
    }
  }

  fun stop(): Job = viewModelScope.launch {
    when (val result = repository.stopTimer()) {
      is ClockifyResult.Success -> applyEntry(null)
      is ClockifyResult.Failure -> mutableUiState.value = TimerUiState.Error(result.error)
    }
  }

  /**
   * Advances the displayed elapsed time once a second while [uiState] is [TimerUiState.Running].
   * Meant to be launched from the screen's `repeatOnLifecycle(STARTED)` block so it is cancelled,
   * not merely idle, once the composition stops (PLANNING.md "Elapsed ticker"). Recomputes from
   * [TimerUiState.Running.startInstant] and [clock] each tick rather than incrementing a counter,
   * so it can't drift. Tested with virtual time by launching it and calling `advanceTimeBy`, never
   * real sleeps.
   */
  suspend fun runElapsedTicker() {
    while (coroutineContext.isActive) {
      val current = mutableUiState.value
      if (current is TimerUiState.Running) {
        mutableUiState.value =
          current.copy(elapsedSeconds = Duration.between(current.startInstant, clock()).seconds)
      }
      delay(TICK_INTERVAL_MS)
    }
  }

  private suspend fun loadRunning() {
    mutableIsRefreshing.value = true
    try {
      settingsPrimed.await()
      when (val result = repository.fetchRunningEntry()) {
        is ClockifyResult.Success -> applyEntry(result.value)
        is ClockifyResult.Failure -> mutableUiState.value = TimerUiState.Error(result.error)
      }
    } finally {
      mutableIsRefreshing.value = false
    }
  }

  private suspend fun applyEntry(entry: TimeEntryDto?) {
    mutableUiState.value =
      if (entry == null) {
        val hasDefault = settingsStore.currentSettings().defaultProjectId != null
        TimerUiState.Idle(hasDefaultProject = hasDefault)
      } else {
        val project = entry.projectId?.let { resolveProject(it) }
        TimerUiState.Running(
          projectId = entry.projectId,
          projectName = project?.name,
          projectColor = project?.color?.let(::parseProjectColor),
          startInstant = entry.timeInterval.start,
          elapsedSeconds = Duration.between(entry.timeInterval.start, clock()).seconds,
          description = entry.description?.takeIf { it.isNotBlank() },
        )
      }
  }

  // Cosmetic-only lookup: a failed or unresolved project must never turn an otherwise-successful
  // running-entry fetch into an error state, same tolerance as
  // TaskPickerViewModel.resolveProjectName. Resolves name and color from the same call so no
  // second network request is made just to look up the color.
  private suspend fun resolveProject(projectId: String): ProjectDto? =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value.firstOrNull { it.id == projectId }
      is ClockifyResult.Failure -> null
    }
}
