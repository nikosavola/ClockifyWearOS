package fi.nikosavola.clockifywear.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val TASK_ID = "5f8a1b2c3d4e5f6a7b8c9d30"
private const val TASK_NAME = "Design review"
private const val API_KEY = "test-api-key"
private const val WAIT_TIMEOUT_MS = 5_000L
private const val STARTED_MARKER = "started-marker"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskPickerScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var settingsStore: SettingsStore
  private lateinit var repository: ClockifyRepository

  @Before
  fun setUp() {
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
  }

  private fun primeIdentity() = runBlocking {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
  }

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun `tapping a task starts the timer and navigates back via onStarted`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("""[{"id": "$TASK_ID", "name": "$TASK_NAME"}]"""))
    server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "Website"}]"""))
    server.enqueue(MockResponse().setResponseCode(404)) // stop: nothing was running
    server.enqueue(
      MockResponse()
        .setBody(
          """{"id": "e1", "projectId": "$PROJECT_ID", "timeInterval": {"start": "2026-07-31T09:00:00Z"}}"""
        )
    )
    val viewModel = TaskPickerViewModel(repository, settingsStore, PROJECT_ID)

    // onStarted flips a plain var that nothing in the composition reads, so Compose's Robolectric
    // idling (driven by semantics-tree queries) would never notice it changed and waitUntil would
    // hang until timeout. Routing it through composed state that's actually rendered keeps the
    // assertion on the same semantics-query-based waiting the rest of this suite already relies on.
    var started by mutableStateOf(false)

    composeRule.setContent {
      TaskPickerScreen(
        viewModel = viewModel,
        onStarted = { started = true },
        onNavigateToSettings = {},
      )
      if (started) Text(text = STARTED_MARKER)
    }

    waitForText(TASK_NAME)
    composeRule.onNodeWithText(TASK_NAME).performClick()

    waitForText(STARTED_MARKER)
    assertTrue(started)
  }
}
