package fi.nikosavola.clockifywear.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import fi.nikosavola.clockifywear.ClockifyApp

/**
 * Read-only AppFunctions: what an on-device agent can ask about the user's Clockify state.
 *
 * Deliberately no start/stop yet. A timer an agent started would not update the ongoing
 * notification or the Tile, because both are driven by `TimerViewModel`'s state transitions and no
 * UI is composed when an agent calls in. Whether the notifier should move to a process-level
 * observer of the repository is part of the write-function work, not this one.
 *
 * The class is abstract with no subclass in this repository on purpose: KSP generates the concrete
 * service (named [SERVICE_NAME], in this package) plus the schema XML, and the manifest registers
 * that generated class.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
  serviceName = "ClockifyAppFunctionService",
  appFunctionXmlFileName = "clockify_app_function_service",
)
abstract class BaseClockifyAppFunctionService : AppFunctionService() {
  // Cast is safe: the manifest gives this app ClockifyApp as its Application, and the system only
  // binds this service on API 36+ where that is already true.
  private val appFunctions: ClockifyAppFunctions
    get() = ClockifyAppFunctions((application as ClockifyApp).appContainer.repository)

  /** Returns the Clockify timer that is running right now, if any. */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun getRunningTimer(): RunningTimer = appFunctions.runningTimer()

  /** Lists the active Clockify projects the user can track time against. */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun listProjects(): List<ProjectSummary> = appFunctions.projects()

  /** Lists the entries the user tracked most recently, newest first. */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun listRecentEntries(): List<RecentTimeEntry> = appFunctions.recentEntries()
}
