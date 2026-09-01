package fi.nikosavola.clockifywear.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.ui.projects.parseProjectColor
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @param repository the data source for entries in a date range and the cached project list.
 * @param settingsPrimed awaited before the first repository call, same discipline as
 *   [fi.nikosavola.clockifywear.ui.timer.TimerViewModel]; see
 *   [fi.nikosavola.clockifywear.di.AppContainer] for why skipping this can 401 a cold start.
 * @param clock injectable so bucket-boundary tests use virtual instants, not the wall clock.
 * @param zoneId injectable for the same reason; defaults to the device's own zone.
 */
class SummaryViewModel(
  private val repository: ClockifyRepository,
  private val settingsPrimed: Deferred<Settings> = CompletableDeferred(Settings()),
  private val clock: () -> Instant = Instant::now,
  private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<SummaryUiState>(SummaryUiState.Loading)
  val uiState: StateFlow<SummaryUiState> = mutableUiState.asStateFlow()

  // Single-flight: load() only returns a Job (doesn't suspend until done), so a caller like
  // SummaryScreen's repeatOnLifecycle(RESUMED) { load() } can otherwise fire a second, overlapping
  // request - e.g. a quick background/foreground cycle while the first fetch is still in flight -
  // and the two responses can then land out of order and overwrite each other. Cancelling any
  // still-running previous load before starting a new one keeps only the latest request live.
  private var loadJob: Job? = null

  /** Returns the launched [Job] so tests can `join()` a real MockWebServer round trip. */
  fun load(): Job {
    loadJob?.cancel()
    val job = viewModelScope.launch {
      settingsPrimed.await()
      val now = clock()
      val boundaries = summaryBoundaries(now, zoneId)
      when (val result = repository.timeEntriesBetween(boundaries.lastWeekStart, now)) {
        is ClockifyResult.Failure -> {
          mutableUiState.value = SummaryUiState.Error(result.error)
        }
        is ClockifyResult.Success -> {
          // Skips the projects()/cache lookup for an empty range, same as RecentsViewModel: no
          // entries means nothing to resolve, and it's an extra network round trip on a watch.
          val projectsById =
            if (result.value.isEmpty()) emptyMap() else resolveProjects().associateBy { it.id }
          mutableUiState.value =
            SummaryUiState.Loaded(
              today = section(result.value, projectsById, boundaries.todayStart, now, now),
              thisWeek =
                section(
                  result.value,
                  projectsById,
                  boundaries.thisWeekStart,
                  boundaries.todayStart,
                  now,
                ),
              lastWeek =
                section(
                  result.value,
                  projectsById,
                  boundaries.lastWeekStart,
                  boundaries.thisWeekStart,
                  now,
                ),
            )
        }
      }
    }
    loadJob = job
    return job
  }

  private fun section(
    entries: List<TimeEntryDto>,
    projectsById: Map<String, ProjectDto>,
    from: Instant,
    until: Instant,
    now: Instant,
  ): SummarySection {
    val displayEntries =
      entries
        .filter { it.timeInterval.start >= from && it.timeInterval.start < until }
        .sortedByDescending { it.timeInterval.start }
        .map { it.toDisplay(projectsById, now) }
    return SummarySection(
      totalSeconds = displayEntries.sumOf { it.durationSeconds },
      entries = displayEntries,
    )
  }

  private fun TimeEntryDto.toDisplay(
    projectsById: Map<String, ProjectDto>,
    now: Instant,
  ): SummaryEntryDisplay {
    val project = projectsById[projectId]
    val end = timeInterval.end ?: now
    return SummaryEntryDisplay(
      id = id,
      // Falls back to the raw project ID when it doesn't resolve (an archived project, or a failed
      // lookup), same as RecentsViewModel/TimerViewModel - only a genuinely absent projectId gets
      // the screen's localized "No project" label, so an unresolved-but-real project doesn't read
      // as if the entry had none.
      projectName = project?.name ?: projectId,
      projectColor = parseProjectColor(project?.color),
      description = description,
      durationSeconds = Duration.between(timeInterval.start, end).seconds.coerceAtLeast(0),
    )
  }

  // Cosmetic-only lookup: a failed call must never turn an otherwise-successful summary fetch into
  // an error state, same tolerance as RecentsViewModel.resolveProjects.
  private suspend fun resolveProjects(): List<ProjectDto> =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value
      is ClockifyResult.Failure -> emptyList()
    }
}
