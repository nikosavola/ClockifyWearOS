package fi.nikosavola.clockifywear.data

import fi.nikosavola.clockifywear.data.api.ClockifyApi
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.StartTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.StopTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.TaskDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.data.api.dto.UserDto
import fi.nikosavola.clockifywear.data.api.dto.WorkspaceDto
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

// PLANNING.md "Clockify API sketch".
internal const val PROJECTS_PAGE_SIZE = 200

// Bounds pagination so a server that never returns a short page (broken filtering, an infinite
// redirect loop, etc.) cannot spin the watch radio forever. 25 pages * 200 = 5,000 projects, far
// beyond any real Clockify workspace.
internal const val MAX_PROJECT_PAGES = 25

// PLANNING.md "Recents shown": 10 *after* dedup.
internal const val RECENT_ENTRIES_LIMIT = 10

// Fetched before dedup, so repeatedly tracking the same project still yields RECENT_ENTRIES_LIMIT
// distinct suggestions. Requesting only RECENT_ENTRIES_LIMIT would silently show fewer.
internal const val RECENT_ENTRIES_FETCH_SIZE = 50

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_NOT_FOUND = 404

/**
 * The single repository for Clockify data: wraps [api] with settings-backed identity, a project
 * cache, and start/stop timer sequencing. Every public function returns [ClockifyResult] instead of
 * throwing for the expected failure modes (auth, rate limit, offline, parse). Coroutine
 * cancellation is the one exception: every catch block below rethrows [CancellationException]
 * before anything else, so a cancelled caller sees cancellation, not a wrapped "offline" failure.
 *
 * @param api the Retrofit client used for every network call.
 * @param settingsStore persisted identity (workspaceId, userId) and the api key.
 * @param projectCache on-disk cache backing [projects].
 * @param clock injectable so timer start/stop timestamps are testable without depending on the real
 *   wall clock.
 */
class ClockifyRepository(
  private val api: ClockifyApi,
  private val settingsStore: SettingsStore,
  private val projectCache: ProjectCache,
  private val clock: () -> Instant = Instant::now,
) {
  private val mutableRunningEntry = MutableStateFlow<TimeEntryDto?>(null)

  /** Updated after start/stop/fetch complete; not refreshed on any external schedule here. */
  val runningEntry: StateFlow<TimeEntryDto?> = mutableRunningEntry.asStateFlow()

  /** Validates [apiKey] via `GET /user`, then resolves and persists the workspace to use. */
  suspend fun signIn(apiKey: String): ClockifyResult<UserDto> {
    settingsStore.setApiKey(apiKey)
    val result =
      when (val userResult = runCatchingClockify { api.getCurrentUser() }) {
        is ClockifyResult.Failure -> {
          userResult
        }
        is ClockifyResult.Success -> {
          persistResolvedWorkspace(userResult.value)
        }
      }
    if (result is ClockifyResult.Failure) {
      // Never leave an unvalidated or rejected key persisted.
      settingsStore.setApiKey(null)
    }
    return result
  }

  suspend fun workspaces(): ClockifyResult<List<WorkspaceDto>> = runCatchingClockify {
    api.getWorkspaces()
  }

  /**
   * Serves the cache immediately when fresh (and [forceRefresh] is false); otherwise fetches all
   * pages, filters archived projects, and rewrites the cache.
   */
  suspend fun projects(forceRefresh: Boolean = false): ClockifyResult<List<ProjectDto>> {
    if (!forceRefresh) {
      val cached = projectCache.read()
      if (cached != null && !projectCache.isStale(cached)) {
        return ClockifyResult.Success(cached.projects)
      }
    }
    return when (val workspaceResult = requireWorkspaceId()) {
      is ClockifyResult.Failure -> {
        workspaceResult
      }
      is ClockifyResult.Success -> {
        fetchAndCacheProjects(workspaceResult.value)
      }
    }
  }

  suspend fun tasks(projectId: String): ClockifyResult<List<TaskDto>> =
    when (val workspaceResult = requireWorkspaceId()) {
      is ClockifyResult.Failure -> {
        workspaceResult
      }
      is ClockifyResult.Success -> {
        runCatchingClockify {
          api.getProjectTasks(workspaceResult.value, projectId, isActive = true)
        }
      }
    }

  /**
   * Always stops any running entry first (a 404 there means nothing was running, treated as
   * success), then starts the new entry. On success, updates [runningEntry].
   */
  suspend fun startTimer(
    projectId: String,
    taskId: String? = null,
    description: String? = null,
  ): ClockifyResult<TimeEntryDto> =
    when (val idsResult = requireWorkspaceAndUser()) {
      is ClockifyResult.Failure -> {
        idsResult
      }
      is ClockifyResult.Success -> {
        val (workspaceId, userId) = idsResult.value
        val result = startAfterStopping(workspaceId, userId, projectId, taskId, description)
        if (result is ClockifyResult.Success) {
          mutableRunningEntry.value = result.value
        }
        result
      }
    }

  /** Stops any running entry; a 404 means nothing was running, not an error. */
  suspend fun stopTimer(): ClockifyResult<Unit> =
    when (val idsResult = requireWorkspaceAndUser()) {
      is ClockifyResult.Failure -> {
        idsResult
      }
      is ClockifyResult.Success -> {
        val (workspaceId, userId) = idsResult.value
        val result = stopRunningEntry(workspaceId, userId)
        if (result is ClockifyResult.Success) {
          mutableRunningEntry.value = null
        }
        result
      }
    }

  suspend fun fetchRunningEntry(): ClockifyResult<TimeEntryDto?> =
    when (val idsResult = requireWorkspaceAndUser()) {
      is ClockifyResult.Failure -> {
        idsResult
      }
      is ClockifyResult.Success -> {
        val (workspaceId, userId) = idsResult.value
        when (
          val result = runCatchingClockify {
            api.getRunningTimeEntry(workspaceId, userId, inProgress = true)
          }
        ) {
          is ClockifyResult.Failure -> {
            result
          }
          is ClockifyResult.Success -> {
            val entry = result.value.firstOrNull()
            mutableRunningEntry.value = entry
            ClockifyResult.Success(entry)
          }
        }
      }
    }

  /** Fetches recent entries, then dedups by project/task/description and caps at 10. */
  suspend fun recentEntries(): ClockifyResult<List<TimeEntryDto>> =
    when (val idsResult = requireWorkspaceAndUser()) {
      is ClockifyResult.Failure -> {
        idsResult
      }
      is ClockifyResult.Success -> {
        val (workspaceId, userId) = idsResult.value
        when (
          val result = runCatchingClockify {
            api.getRecentTimeEntries(workspaceId, userId, pageSize = RECENT_ENTRIES_FETCH_SIZE)
          }
        ) {
          is ClockifyResult.Failure -> {
            result
          }
          is ClockifyResult.Success -> {
            ClockifyResult.Success(
              result.value
                .distinctBy { Triple(it.projectId, it.taskId, it.description) }
                .take(RECENT_ENTRIES_LIMIT)
            )
          }
        }
      }
    }

  private suspend fun persistResolvedWorkspace(user: UserDto): ClockifyResult<UserDto> =
    when (val workspaceResult = resolveWorkspaceId(user)) {
      is ClockifyResult.Failure -> {
        workspaceResult
      }
      is ClockifyResult.Success -> {
        settingsStore.setUserId(user.id)
        settingsStore.setWorkspaceId(workspaceResult.value)
        settingsStore.setEmail(user.email)
        ClockifyResult.Success(user)
      }
    }

  private suspend fun resolveWorkspaceId(user: UserDto): ClockifyResult<String> {
    val direct = user.activeWorkspace ?: user.defaultWorkspace
    if (direct != null) return ClockifyResult.Success(direct)
    return when (val workspacesResult = runCatchingClockify { api.getWorkspaces() }) {
      is ClockifyResult.Failure -> {
        workspacesResult
      }
      is ClockifyResult.Success -> {
        val first = workspacesResult.value.firstOrNull()
        if (first != null) {
          ClockifyResult.Success(first.id)
        } else {
          ClockifyResult.Failure(ClockifyError.NoWorkspaceFound)
        }
      }
    }
  }

  private suspend fun fetchAndCacheProjects(workspaceId: String): ClockifyResult<List<ProjectDto>> {
    val fetched = mutableListOf<ProjectDto>()
    var page = 1
    while (page <= MAX_PROJECT_PAGES) {
      when (
        val pageResult = runCatchingClockify {
          api.getProjects(workspaceId, archived = false, page = page, pageSize = PROJECTS_PAGE_SIZE)
        }
      ) {
        is ClockifyResult.Failure -> {
          return pageResult
        }
        is ClockifyResult.Success -> {
          fetched += pageResult.value
          if (pageResult.value.size < PROJECTS_PAGE_SIZE) break
          page++
        }
      }
    }
    val nonArchived = fetched.filterNot { it.archived }
    projectCache.write(nonArchived)
    return ClockifyResult.Success(nonArchived)
  }

  private suspend fun requireWorkspaceId(): ClockifyResult<String> {
    val workspaceId = settingsStore.currentSettings().workspaceId
    return if (workspaceId != null) {
      ClockifyResult.Success(workspaceId)
    } else {
      ClockifyResult.Failure(ClockifyError.NotSignedIn)
    }
  }

  private suspend fun requireWorkspaceAndUser(): ClockifyResult<Pair<String, String>> {
    val settings = settingsStore.currentSettings()
    val workspaceId = settings.workspaceId
    val userId = settings.userId
    return if (workspaceId != null && userId != null) {
      ClockifyResult.Success(workspaceId to userId)
    } else {
      ClockifyResult.Failure(ClockifyError.NotSignedIn)
    }
  }

  private suspend fun startAfterStopping(
    workspaceId: String,
    userId: String,
    projectId: String,
    taskId: String?,
    description: String?,
  ): ClockifyResult<TimeEntryDto> {
    val stopResult = stopRunningEntry(workspaceId, userId)
    if (stopResult is ClockifyResult.Failure) return stopResult

    return runCatchingClockify {
      api.startTimeEntry(
        workspaceId,
        StartTimeEntryRequest(
          start = clock(),
          projectId = projectId,
          taskId = taskId,
          description = description,
        ),
      )
    }
  }

  // Always invoked before starting a new entry, and directly by stopTimer(): Clockify's stop
  // endpoint returns 404 when nothing is running, which is the common, expected case here, not
  // a failure.
  private suspend fun stopRunningEntry(workspaceId: String, userId: String): ClockifyResult<Unit> =
    try {
      api.stopTimeEntry(workspaceId, userId, StopTimeEntryRequest(end = clock()))
      ClockifyResult.Success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpException) {
      if (e.code() == HTTP_NOT_FOUND) {
        ClockifyResult.Success(Unit)
      } else {
        ClockifyResult.Failure(httpError(e.code()))
      }
    } catch (e: SerializationException) {
      ClockifyResult.Failure(ClockifyError.ParseError)
    } catch (e: IOException) {
      ClockifyResult.Failure(ClockifyError.Offline)
    }
}

private suspend fun <T> runCatchingClockify(block: suspend () -> T): ClockifyResult<T> =
  try {
    ClockifyResult.Success(block())
  } catch (e: CancellationException) {
    // Coroutine cancellation must propagate, never be swallowed as an API failure.
    throw e
  } catch (e: HttpException) {
    ClockifyResult.Failure(httpError(e.code()))
  } catch (e: SerializationException) {
    ClockifyResult.Failure(ClockifyError.ParseError)
  } catch (e: IOException) {
    ClockifyResult.Failure(ClockifyError.Offline)
  }

private fun httpError(code: Int): ClockifyError =
  when (code) {
    HTTP_UNAUTHORIZED -> ClockifyError.Unauthorized
    HTTP_TOO_MANY_REQUESTS -> ClockifyError.RateLimited
    else -> ClockifyError.Http(code)
  }
