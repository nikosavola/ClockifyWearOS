package fi.nikosavola.clockifywear.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionLimitExceededException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSystemUnknownException
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The logic behind this app's AppFunctions, kept out of the service so it is unit-testable without
 * a bound Android service.
 *
 * Every entry point shifts onto [ioDispatcher] first: the generated service dispatches on the main
 * thread, and `ClockifyRepository.projects()` reads and writes its cache file with blocking `File`
 * I/O, so without this a cold call would stall the main thread the OS dispatches function calls on.
 *
 * @param repository the same instance the UI uses, so an agent reads the state the user sees.
 * @param nowInstant injectable so elapsed time is testable without the real wall clock.
 * @param ioDispatcher injected so tests run this on the test dispatcher instead of real disk.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
internal class ClockifyAppFunctions(
  private val repository: ClockifyRepository,
  private val nowInstant: () -> Instant = Instant::now,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  suspend fun runningTimer(): RunningTimer = withContext(ioDispatcher) { loadRunningTimer() }

  private suspend fun loadRunningTimer(): RunningTimer {
    val entry = repository.fetchRunningEntry().orThrowAppFunction()
    // The in-progress query should only ever hand back an open entry. If the timer stopped between
    // the list and this read, or the API drifts, then nothing is running, and saying exactly that
    // beats reporting an elapsed time alongside `isRunning = false`.
    if (entry == null || entry.timeInterval.end != null) {
      return RunningTimer(isRunning = false)
    }
    return RunningTimer(
      isRunning = true,
      projectName = entry.projectId?.let { id -> projectNameFor(id) },
      description = entry.description,
      elapsedMinutes = Duration.between(entry.timeInterval.start, nowInstant()).toMinutes(),
    )
  }

  suspend fun projects(): List<ProjectSummary> =
    withContext(ioDispatcher) {
      repository.projects().orThrowAppFunction().map {
        ProjectSummary(id = it.id, name = it.name, clientName = it.clientName)
      }
    }

  suspend fun recentEntries(): List<RecentTimeEntry> =
    withContext(ioDispatcher) {
      val projectNames = projectNamesById()
      repository.recentEntries().orThrowAppFunction().map { entry ->
        RecentTimeEntry(
          projectName = entry.projectId?.let(projectNames::get),
          description = entry.description,
          // Null rather than zero for an entry that has no end: Clockify lists the entry that is
          // still running among the recent ones, and reporting that as "0 minutes" would be a
          // wrong answer rather than an absent one.
          durationMinutes =
            entry.timeInterval.end?.let { end ->
              Duration.between(entry.timeInterval.start, end).toMinutes()
            },
        )
      }
    }

  // Best effort: a name is decoration on an otherwise useful answer, so a failed lookup leaves it
  // null rather than failing the whole call an agent asked for.
  private suspend fun projectNameFor(projectId: String): String? = projectNamesById()[projectId]

  private suspend fun projectNamesById(): Map<String, String> =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value.associate { it.id to it.name }
      is ClockifyResult.Failure -> emptyMap()
    }

  private suspend fun <T> ClockifyResult<T>.orThrowAppFunction(): T =
    when (this) {
      is ClockifyResult.Success -> value
      is ClockifyResult.Failure -> throw error.toAppFunctionException()
    }

  // The exception category is what an agent acts on, so each failure picks the category that tells
  // the truth about who can fix it: the request category when only the user can, the system
  // category
  // when the watch or the network is at fault, and the app category only when this app genuinely
  // broke.
  //
  // The permission-required type is the nearest thing alpha13 has to "the user has to go and set
  // something up on the watch"; there is no grant an agent could request, so the message has to say
  // what actually needs to happen.
  private fun ClockifyError.toAppFunctionException(): AppFunctionException =
    when (this) {
      ClockifyError.NotSignedIn ->
        AppFunctionPermissionRequiredException(
          "Clockify is not signed in on the watch. The user has to open the app and enter an API key."
        )
      ClockifyError.Unauthorized ->
        AppFunctionPermissionRequiredException(
          "The stored Clockify API key was rejected. The user has to sign in again on the watch."
        )
      ClockifyError.NoWorkspaceFound ->
        AppFunctionPermissionRequiredException(
          "The Clockify account has no usable workspace for the user to pick on the watch."
        )
      ClockifyError.RateLimited ->
        AppFunctionLimitExceededException("Clockify is rate limiting this app. Try again shortly.")
      ClockifyError.Offline ->
        AppFunctionSystemUnknownException(
          "Clockify could not be reached: the watch has no network."
        )
      is ClockifyError.Http -> AppFunctionAppUnknownException("Clockify returned HTTP $code.")
      ClockifyError.ParseError ->
        AppFunctionAppUnknownException("Clockify's response could not be read.")
    }
}
