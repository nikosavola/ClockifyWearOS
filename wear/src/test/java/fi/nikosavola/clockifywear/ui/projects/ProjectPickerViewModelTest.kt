package fi.nikosavola.clockifywear.ui.projects

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
private const val API_KEY = "test-api-key"
private const val PROJECT_ALPHA_ID = "5f8a1b2c3d4e5f6a7b8c9d21"
private const val PROJECT_BRAVO_ID = "5f8a1b2c3d4e5f6a7b8c9d22"
private const val PROJECT_CHARLIE_ID = "5f8a1b2c3d4e5f6a7b8c9d23"

// Real (not virtual) wall-clock wait: long enough for a real localhost MockWebServer round trip
// to complete if nothing is blocking it, short enough to keep the suite fast.
private const val NEVER_PRIMED_WAIT_MS = 300L

// Deliberately out of both default-first and alphabetical order, so a passing sort test can't be
// an accident of the fixture already being in the expected order.
private fun unsortedProjectsJson(): String =
  """[{"id": "$PROJECT_CHARLIE_ID", "name": "Charlie"}, """ +
    """{"id": "$PROJECT_ALPHA_ID", "name": "alpha"}, """ +
    """{"id": "$PROJECT_BRAVO_ID", "name": "Bravo"}]"""

@RunWith(RobolectricTestRunner::class)
class ProjectPickerViewModelTest {
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

  private suspend fun primeIdentity(defaultProjectId: String? = null) {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    if (defaultProjectId != null) settingsStore.setDefaultProjectId(defaultProjectId)
  }

  @Test
  fun `load sorts the current default project first, then the rest alphabetically`() =
    runTest(testDispatcher) {
      primeIdentity(defaultProjectId = PROJECT_BRAVO_ID)
      server.enqueue(MockResponse().setBody(unsortedProjectsJson()))
      val viewModel = ProjectPickerViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is ProjectPickerUiState.Loaded)
      assertEquals(
        listOf(PROJECT_BRAVO_ID, PROJECT_ALPHA_ID, PROJECT_CHARLIE_ID),
        (state as ProjectPickerUiState.Loaded).projects.map { it.id },
      )
    }

  @Test
  fun `load surfaces the error state on a failed projects call`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(401))
      val viewModel = ProjectPickerViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is ProjectPickerUiState.Error)
      assertEquals(ClockifyError.Unauthorized, (state as ProjectPickerUiState.Error).error)
    }

  // ClockifyRepository.projects() always reads settingsStore.currentSettings() itself (via
  // requireWorkspaceId()) before firing a request, which primes SettingsStore's api-key mirror as
  // a side effect regardless of whether settingsPrimed was awaited first. So, unlike
  // AppContainerTest's use of the mirror-priming-free workspaces() call, asserting the outgoing
  // X-Api-Key header here (or draining the virtual scheduler with runCurrent() and checking
  // requestCount right away) would pass with or without the await() and prove nothing: both
  // versions look identically "not there yet" immediately after a single runCurrent(), because
  // the real DataStore/OkHttp work happens on real threads outside the virtual scheduler either
  // way -- a test that passes both with and without the fix is worse than no test, verified by
  // actually removing the await() and watching this exact assertion still pass. Leaving
  // settingsPrimed permanently incomplete and waiting out real wall-clock time
  // instead proves genuine blocking: with the await() in place, nothing can ever reach the
  // repository while the Deferred is incomplete, no matter how long real time is given to prove
  // otherwise; without it, the real round trip completes well within the wait below.
  @Test
  fun `load awaits settingsPrimed before making any repository call`() =
    runTest(testDispatcher) {
      primeIdentity()
      val settingsPrimed = CompletableDeferred<Settings>()
      val viewModel = ProjectPickerViewModel(repository, settingsStore, settingsPrimed)

      val job = viewModel.load()
      // withTimeoutOrNull's own delay() would run on this test's virtual scheduler and get
      // fast-forwarded immediately once the ViewModel's coroutine looks "idle" from the scheduler's
      // perspective (an indefinite Deferred.await() isn't a scheduled virtual-time event, so there
      // is nothing to wait for on the virtual timeline) -- verified empirically: it returned within
      // 150ms real time despite asking for 300, which would be impossible if it were a genuine
      // wait. Switching to a real dispatcher for the wait itself keeps job.join() as the thing
      // that actually waits (as it reliably does elsewhere in this suite for real round trips)
      // while making the *timeout* a real one, not a virtual one.
      val completed =
        withContext(Dispatchers.Default) { withTimeoutOrNull(NEVER_PRIMED_WAIT_MS) { job.join() } }

      assertEquals(null, completed)
      assertEquals(0, server.requestCount)
      assertTrue(job.isActive)
      assertTrue(viewModel.uiState.value is ProjectPickerUiState.Loading)

      server.enqueue(MockResponse().setBody("[]"))
      settingsPrimed.complete(Settings())
      job.join()

      assertTrue(viewModel.uiState.value is ProjectPickerUiState.Loaded)
    }
}
