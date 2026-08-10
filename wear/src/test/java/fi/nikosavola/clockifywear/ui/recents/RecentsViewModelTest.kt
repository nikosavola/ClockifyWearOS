package fi.nikosavola.clockifywear.ui.recents

import androidx.compose.ui.graphics.Color
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val API_KEY = "test-api-key"
private const val PROJECT_ALPHA_ID = "5f8a1b2c3d4e5f6a7b8c9d21"
private const val PROJECT_UNKNOWN_ID = "5f8a1b2c3d4e5f6a7b8c9d22"
private const val PROJECT_BRAVO_ID = "5f8a1b2c3d4e5f6a7b8c9d23"
private const val TASK_ID = "5f8a1b2c3d4e5f6a7b8c9d30"

// Real (not virtual) wall-clock wait: long enough for a real localhost MockWebServer round trip
// to complete if nothing is blocking it, short enough to keep the suite fast.
private const val NEVER_PRIMED_WAIT_MS = 300L

private fun timeEntryJson(
  id: String,
  projectId: String?,
  taskId: String? = null,
  description: String? = null,
): String {
  val fields = mutableListOf(""""id": "$id"""")
  if (projectId != null) fields += """"projectId": "$projectId""""
  if (taskId != null) fields += """"taskId": "$taskId""""
  if (description != null) fields += """"description": "$description""""
  fields += """"timeInterval": {"start": "2026-07-31T09:00:00Z"}"""
  return fields.joinToString(prefix = "{", postfix = "}")
}

private fun entriesListJson(vararg entries: String): String =
  entries.joinToString(prefix = "[", postfix = "]")

private fun projectsListJson(): String =
  """[{"id": "$PROJECT_ALPHA_ID", "name": "Alpha Project"}]"""

private fun projectsListJsonWithColors(): String =
  """[{"id": "$PROJECT_ALPHA_ID", "name": "Alpha Project", "color": "#1976D2"}, """ +
    """{"id": "$PROJECT_BRAVO_ID", "name": "Bravo Project", "color": "not-a-color"}]"""

@RunWith(RobolectricTestRunner::class)
class RecentsViewModelTest {
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

  private suspend fun primeIdentity() {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
  }

  @Test
  fun `load resolves project names, falls back to raw id, and drops projectless entries`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse()
          .setBody(
            entriesListJson(
              timeEntryJson("e1", projectId = PROJECT_ALPHA_ID, description = "known"),
              timeEntryJson("e2", projectId = PROJECT_UNKNOWN_ID, description = "unknown"),
              timeEntryJson("e3", projectId = null, description = "projectless"),
            )
          )
      )
      server.enqueue(MockResponse().setBody(projectsListJson()))
      val viewModel = RecentsViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is RecentsUiState.Loaded)
      val entries = (state as RecentsUiState.Loaded).entries
      assertEquals(2, entries.size)
      assertEquals("Alpha Project", entries[0].projectName)
      assertEquals(PROJECT_UNKNOWN_ID, entries[1].projectName)
      assertTrue(entries.none { it.description == "projectless" })
    }

  @Test
  fun `load resolves the matched project's color, falling back to null when unresolvable or malformed`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse()
          .setBody(
            entriesListJson(
              timeEntryJson("e1", projectId = PROJECT_ALPHA_ID),
              timeEntryJson("e2", projectId = PROJECT_BRAVO_ID),
              timeEntryJson("e3", projectId = PROJECT_UNKNOWN_ID),
            )
          )
      )
      server.enqueue(MockResponse().setBody(projectsListJsonWithColors()))
      val viewModel = RecentsViewModel(repository, settingsStore)

      viewModel.load().join()

      val entries = (viewModel.uiState.value as RecentsUiState.Loaded).entries
      assertEquals(Color(red = 0x19, green = 0x76, blue = 0xD2), entries[0].projectColor)
      assertNull(entries[1].projectColor) // matched project, malformed color string
      assertNull(entries[2].projectColor) // unresolvable project
    }

  @Test
  fun `load surfaces the error state on a failed recentEntries call`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(401))
      val viewModel = RecentsViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is RecentsUiState.Error)
      assertEquals(ClockifyError.Unauthorized, (state as RecentsUiState.Error).error)
    }

  @Test
  fun `an empty recentEntries result reaches Loaded(emptyList) and skips the projects call`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody("[]"))
      val viewModel = RecentsViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is RecentsUiState.Loaded)
      assertTrue((state as RecentsUiState.Loaded).entries.isEmpty())
      // Only the recentEntries request happened; a projects() call would show up as a second one.
      assertEquals(1, server.requestCount)
    }

  @Test
  fun `restart starts the timer with the entry's fields, persists defaults, and reaches Started`() =
    runTest(testDispatcher) {
      primeIdentity()
      val entry =
        RecentEntryDisplay(
          projectId = PROJECT_ALPHA_ID,
          taskId = TASK_ID,
          description = "restarted work",
          projectName = "Alpha Project",
        )
      server.enqueue(MockResponse().setResponseCode(404)) // stop: nothing was running
      server.enqueue(
        MockResponse().setBody(timeEntryJson("new-entry", projectId = PROJECT_ALPHA_ID))
      )
      val viewModel = RecentsViewModel(repository, settingsStore)

      viewModel.restart(entry).join()

      assertTrue(viewModel.uiState.value is RecentsUiState.Started)
      server.takeRequest() // the stop-first request; not under test here
      val startRequest = server.takeRequest()
      val startBody = startRequest.body.readUtf8()
      assertTrue(startBody.contains(""""projectId":"$PROJECT_ALPHA_ID""""))
      assertTrue(startBody.contains(""""taskId":"$TASK_ID""""))
      assertTrue(startBody.contains(""""description":"restarted work""""))
      assertEquals(PROJECT_ALPHA_ID, settingsStore.currentSettings().defaultProjectId)
      assertEquals(TASK_ID, settingsStore.currentSettings().defaultTaskId)
    }

  @Test
  fun `a startTimer failure during restart reaches Error, not Started`() =
    runTest(testDispatcher) {
      primeIdentity()
      val entry =
        RecentEntryDisplay(
          projectId = PROJECT_ALPHA_ID,
          taskId = null,
          description = null,
          projectName = "Alpha Project",
        )
      server.enqueue(MockResponse().setResponseCode(500))
      val viewModel = RecentsViewModel(repository, settingsStore)

      viewModel.restart(entry).join()

      assertTrue(viewModel.uiState.value is RecentsUiState.Error)
    }

  // Same reasoning as ProjectPickerViewModelTest's equivalent test: recentEntries() also primes
  // SettingsStore's api-key mirror as a side effect via requireWorkspaceAndUser(), so asserting the
  // outgoing header or draining the virtual scheduler once would pass with or without the await()
  // and prove nothing. Leaving settingsPrimed permanently incomplete and waiting out real
  // wall-clock
  // time instead proves genuine blocking.
  @Test
  fun `load awaits settingsPrimed before making any repository call`() =
    runTest(testDispatcher) {
      primeIdentity()
      val settingsPrimed = CompletableDeferred<Settings>()
      val viewModel = RecentsViewModel(repository, settingsStore, settingsPrimed)

      val job = viewModel.load()
      val completed =
        withContext(Dispatchers.Default) { withTimeoutOrNull(NEVER_PRIMED_WAIT_MS) { job.join() } }

      assertNull(completed)
      assertEquals(0, server.requestCount)
      assertTrue(job.isActive)
      assertTrue(viewModel.uiState.value is RecentsUiState.Loading)

      server.enqueue(MockResponse().setBody("[]"))
      settingsPrimed.complete(Settings())
      job.join()

      assertTrue(viewModel.uiState.value is RecentsUiState.Loaded)
    }
}
