package fi.nikosavola.clockifywear.ui.tasks

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val PROJECT_NAME = "Website redesign"
private const val TASK_ID = "5f8a1b2c3d4e5f6a7b8c9d30"
private const val API_KEY = "test-api-key"

private fun taskJson(id: String, name: String): String = """{"id": "$id", "name": "$name"}"""

private fun tasksListJson(vararg tasks: Pair<String, String>): String =
  tasks.joinToString(prefix = "[", postfix = "]") { (id, name) -> taskJson(id, name) }

private fun projectsListJson(): String = """[{"id": "$PROJECT_ID", "name": "$PROJECT_NAME"}]"""

private fun timeEntryJson(id: String): String =
  """{"id": "$id", "projectId": "$PROJECT_ID", "timeInterval": {"start": "2026-07-31T09:00:00Z"}}"""

// viewModelScope runs on Dispatchers.Main; job.join() (not advanceUntilIdle()) waits out the real
// MockWebServer round trips these repository calls make, per the TimerViewModelTest convention.
@RunWith(RobolectricTestRunner::class)
class TaskPickerViewModelTest {
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

  private fun viewModel() = TaskPickerViewModel(repository, settingsStore, PROJECT_ID)

  @Test
  fun `load with non-empty tasks reaches Loaded with the resolved project name`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(tasksListJson(TASK_ID to "Design review")))
      server.enqueue(MockResponse().setBody(projectsListJson()))
      val viewModel = viewModel()

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is TaskPickerUiState.Loaded)
      assertEquals(PROJECT_NAME, (state as TaskPickerUiState.Loaded).projectName)
      assertEquals(1, state.tasks.size)
    }

  @Test
  fun `load falls back to the raw project id when the name lookup fails`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(tasksListJson(TASK_ID to "Design review")))
      server.enqueue(MockResponse().setResponseCode(500))
      val viewModel = viewModel()

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is TaskPickerUiState.Loaded)
      assertEquals(PROJECT_ID, (state as TaskPickerUiState.Loaded).projectName)
    }

  @Test
  fun `load with an empty task list auto-starts the timer and persists no default task`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody("[]")) // tasks: empty
      server.enqueue(MockResponse().setResponseCode(404)) // stop: nothing was running
      server.enqueue(MockResponse().setBody(timeEntryJson("started")))
      val viewModel = viewModel()

      viewModel.load().join()

      assertTrue(viewModel.uiState.value is TaskPickerUiState.Started)
      val tasksRequest = server.takeRequest()
      assertEquals(
        "/workspaces/$WORKSPACE_ID/projects/$PROJECT_ID/tasks",
        tasksRequest.requestUrl!!.encodedPath,
      )
      val stopRequest = server.takeRequest()
      assertEquals("PATCH", stopRequest.method)
      val startRequest = server.takeRequest()
      assertEquals("POST", startRequest.method)
      assertEquals(PROJECT_ID, settingsStore.currentSettings().defaultProjectId)
      assertNull(settingsStore.currentSettings().defaultTaskId)
    }

  @Test
  fun `selectTask with an explicit task id persists it and reaches Started`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(tasksListJson(TASK_ID to "Design review")))
      server.enqueue(MockResponse().setBody(projectsListJson()))
      val viewModel = viewModel()
      viewModel.load().join()

      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody(timeEntryJson("started")))
      viewModel.selectTask(TASK_ID).join()

      assertTrue(viewModel.uiState.value is TaskPickerUiState.Started)
      assertEquals(PROJECT_ID, settingsStore.currentSettings().defaultProjectId)
      assertEquals(TASK_ID, settingsStore.currentSettings().defaultTaskId)
    }

  @Test
  fun `selecting no specific task on a project with tasks clears a stale default task`() =
    runTest(testDispatcher) {
      primeIdentity()
      settingsStore.setDefaultTaskId("stale-task-id")
      server.enqueue(MockResponse().setBody(tasksListJson(TASK_ID to "Design review")))
      server.enqueue(MockResponse().setBody(projectsListJson()))
      val viewModel = viewModel()
      viewModel.load().join()

      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody(timeEntryJson("started")))
      viewModel.selectTask(null).join()

      assertTrue(viewModel.uiState.value is TaskPickerUiState.Started)
      assertNull(settingsStore.currentSettings().defaultTaskId)
    }

  @Test
  fun `a tasks fetch failure reaches the error state`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(401))
      val viewModel = viewModel()

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is TaskPickerUiState.Error)
      assertEquals(ClockifyError.Unauthorized, (state as TaskPickerUiState.Error).error)
    }

  @Test
  fun `a startTimer failure on the auto-start path reaches Error, not Started`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody("[]")) // tasks: empty
      server.enqueue(MockResponse().setResponseCode(500)) // stop fails with a real error
      val viewModel = viewModel()

      viewModel.load().join()

      assertTrue(viewModel.uiState.value is TaskPickerUiState.Error)
    }

  @Test
  fun `a startTimer failure on selectTask reaches Error, not Started`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody(tasksListJson(TASK_ID to "Design review")))
      server.enqueue(MockResponse().setBody(projectsListJson()))
      val viewModel = viewModel()
      viewModel.load().join()

      server.enqueue(MockResponse().setResponseCode(500))
      viewModel.selectTask(TASK_ID).join()

      assertTrue(viewModel.uiState.value is TaskPickerUiState.Error)
    }
}
