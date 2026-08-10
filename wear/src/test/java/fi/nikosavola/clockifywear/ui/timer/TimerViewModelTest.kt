package fi.nikosavola.clockifywear.ui.timer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import fi.nikosavola.clockifywear.ui.projects.parseProjectColor
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val TASK_ID = "5f8a1b2c3d4e5f6a7b8c9d30"
private const val API_KEY = "test-api-key"
// Long enough that the assertion below reliably lands before the response arrives, short enough
// to not slow the suite down: this is a real (not virtual) delay, since MockWebServer's socket
// round trip is real IO regardless of which dispatcher the test coroutine uses.
private const val IN_FLIGHT_RESPONSE_DELAY_MS = 200L

private fun timeEntryJson(
  id: String,
  start: String = "2026-07-31T09:00:00Z",
  description: String? = null,
): String =
  """{"id": "$id", "projectId": "$PROJECT_ID", "timeInterval": {"start": "$start"}""" +
    (description?.let { ""","description": "$it"""" } ?: "") +
    "}"

// getRunningTimeEntry returns a List<TimeEntryDto> (0 or 1 elements); startTimeEntry/stopTimeEntry
// return a single TimeEntryDto. Mixing these up parses fine as a list-of-one either way is wrong:
// an unwrapped object here would fail to parse as a JSON array and surface a ParseError instead.
private fun runningEntryListJson(
  id: String,
  start: String = "2026-07-31T09:00:00Z",
  description: String? = null,
): String = "[${timeEntryJson(id, start, description)}]"

// viewModelScope runs on Dispatchers.Main; setMain(testDispatcher) plus runTest(testDispatcher)
// share one virtual-time scheduler. Network/disk-bound actions are awaited with job.join() (a
// real suspension, correct regardless of which thread resumes it) rather than advanceUntilIdle(),
// which only drains what is already queued and does not wait out MockWebServer's real round trip.
// The ticker itself does no I/O, so once Running is reached, advanceTimeBy()/runCurrent() safely
// drive it with virtual time only, per PLANNING.md.
@RunWith(RobolectricTestRunner::class)
class TimerViewModelTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var server: MockWebServer
  private lateinit var settingsStore: SettingsStore
  private lateinit var repository: ClockifyRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    server = MockWebServer()
    server.start()
    val api = createClockifyApi(apiKey = { API_KEY }, baseUrl = server.url("/").toString())
    settingsStore =
      SettingsStore(
        PreferenceDataStoreFactory.create(
          produceFile = { tempFolder.newFile("settings.preferences_pb") }
        )
      )
    val projectCache = ProjectCache(File(tempFolder.root, "projects.json"))
    repository = ClockifyRepository(api, settingsStore, projectCache)
  }

  @After
  fun tearDown() {
    server.shutdown()
    Dispatchers.resetMain()
  }

  private suspend fun primeIdentity(
    defaultProjectId: String? = null,
    defaultTaskId: String? = null,
  ) {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
    if (defaultProjectId != null) settingsStore.setDefaultProjectId(defaultProjectId)
    if (defaultTaskId != null) settingsStore.setDefaultTaskId(defaultTaskId)
  }

  @Test
  fun `onForeground with nothing running and no default project surfaces Idle without a default`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody("[]"))
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Idle)
      assertEquals(false, (state as TimerUiState.Idle).hasDefaultProject)
    }

  @Test
  fun `onForeground with a running entry surfaces Running with elapsed computed from start`() =
    runTest(testDispatcher) {
      primeIdentity()
      val start = Instant.parse("2026-07-31T09:00:00Z")
      val now = start.plusSeconds(90)
      server.enqueue(
        MockResponse().setBody(runningEntryListJson("running", start = start.toString()))
      )
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel = TimerViewModel(repository, settingsStore, clock = { now })

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(PROJECT_ID, (state as TimerUiState.Running).projectId)
      assertEquals(90L, state.elapsedSeconds)
    }

  @Test
  fun `onForeground with a running entry resolves the project name from the projects list`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "Website"}]"""))
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(PROJECT_ID, (state as TimerUiState.Running).projectId)
      assertEquals("Website", state.projectName)
    }

  @Test
  fun `onForeground with a running entry resolves the project color from the projects list`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(
        MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "Website", "color": "#1976D2"}]""")
      )
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(parseProjectColor("#1976D2"), (state as TimerUiState.Running).projectColor)
    }

  @Test
  fun `onForeground with a running entry tolerates a project with no color`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "Website"}]"""))
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals("Website", (state as TimerUiState.Running).projectName)
      assertEquals(null, state.projectColor)
    }

  @Test
  fun `onForeground with a running entry tolerates no matching project for color`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(MockResponse().setBody("[]")) // no project matches the running entry's id
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(null, (state as TimerUiState.Running).projectColor)
    }

  @Test
  fun `onForeground with a running entry tolerates a failed project-name lookup`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(MockResponse().setResponseCode(500))
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(PROJECT_ID, (state as TimerUiState.Running).projectId)
      assertEquals(null, state.projectName)
    }

  @Test
  fun `onForeground with a running entry populates description from the entry`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse().setBody(runningEntryListJson("running", description = "Writing docs"))
      )
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals("Writing docs", (state as TimerUiState.Running).description)
    }

  @Test
  fun `onForeground with a running entry with no description surfaces a null description`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(null, (state as TimerUiState.Running).description)
    }

  @Test
  fun `onForeground with a running entry with a blank description surfaces a null description`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running", description = "   ")))
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Running)
      assertEquals(null, (state as TimerUiState.Running).description)
    }

  @Test
  fun `stop transitions Running back to Idle`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(runningEntryListJson("running")))
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel = TimerViewModel(repository, settingsStore)
      viewModel.onForeground().join()
      assertTrue(viewModel.uiState.value is TimerUiState.Running)

      server.enqueue(MockResponse().setBody(timeEntryJson("stopped")))
      viewModel.stop().join()

      assertTrue(viewModel.uiState.value is TimerUiState.Idle)
    }

  @Test
  fun `unauthorized surfaces the auth error state`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(401))
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.onForeground().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Error)
      assertEquals(ClockifyError.Unauthorized, (state as TimerUiState.Error).error)
    }

  @Test
  fun `start with no default project surfaces Idle without a default and makes no request`() =
    runTest(testDispatcher) {
      primeIdentity(defaultProjectId = null)
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.start().join()

      val state = viewModel.uiState.value
      assertTrue(state is TimerUiState.Idle)
      assertEquals(false, (state as TimerUiState.Idle).hasDefaultProject)
      assertEquals(0, server.requestCount)
    }

  @Test
  fun `start sends the default task id along with the default project id`() =
    runTest(testDispatcher) {
      primeIdentity(defaultProjectId = PROJECT_ID, defaultTaskId = TASK_ID)
      server.enqueue(MockResponse().setResponseCode(404)) // stop: nothing was running
      server.enqueue(MockResponse().setBody(timeEntryJson("new-entry")))
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel = TimerViewModel(repository, settingsStore)

      viewModel.start().join()

      server.takeRequest() // the stop-first request; not under test here
      val startRequest = server.takeRequest()
      assertTrue(startRequest.body.readUtf8().contains(""""taskId":"$TASK_ID""""))
    }

  @Test
  fun `runElapsedTicker advances elapsed using virtual time, not real sleeps`() =
    runTest(testDispatcher) {
      primeIdentity()
      val start = Instant.parse("2026-07-31T09:00:00Z")
      server.enqueue(
        MockResponse().setBody(runningEntryListJson("running", start = start.toString()))
      )
      server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
      val viewModel =
        TimerViewModel(
          repository,
          settingsStore,
          clock = { start.plusMillis(testScheduler.currentTime) },
        )

      viewModel.onForeground().join()
      assertEquals(0L, (viewModel.uiState.value as TimerUiState.Running).elapsedSeconds)

      val tickerJob = launch { viewModel.runElapsedTicker() }
      advanceTimeBy(2_500)
      runCurrent()

      val running = viewModel.uiState.value as TimerUiState.Running
      assertEquals(2L, running.elapsedSeconds)
      tickerJob.cancel()
    }

  @Test
  fun `isRefreshing is true while a foreground load is in flight and false once it completes`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse()
          .setBody("[]")
          .setBodyDelay(IN_FLIGHT_RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS)
      )
      val viewModel = TimerViewModel(repository, settingsStore)
      assertEquals(false, viewModel.isRefreshing.value)

      val job = viewModel.onForeground()
      runCurrent() // runs the coroutine up to the real network suspension point
      assertEquals(true, viewModel.isRefreshing.value)

      job.join()
      assertEquals(false, viewModel.isRefreshing.value)
    }

  @Test
  fun `isRefreshing returns to false after a failed request, not just a successful one`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse()
          .setResponseCode(500)
          .setBodyDelay(IN_FLIGHT_RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS)
      )
      val viewModel = TimerViewModel(repository, settingsStore)

      val job = viewModel.onForeground()
      runCurrent()
      assertEquals(true, viewModel.isRefreshing.value)

      job.join()
      assertEquals(false, viewModel.isRefreshing.value)
      assertTrue(viewModel.uiState.value is TimerUiState.Error)
    }
}
