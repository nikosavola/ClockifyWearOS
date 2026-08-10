package fi.nikosavola.clockifywear.ui.timer

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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
private const val API_KEY = "test-api-key"
private const val WAIT_TIMEOUT_MS = 5_000L

// Robolectric's default (LEGACY) graphics shim does not support everything Compose's text/layout
// pipeline needs; NATIVE mode is the documented combination for Compose UI tests under Robolectric.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerScreenTest {
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

  private fun primeIdentity(defaultProjectId: String? = null) = runBlocking {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
    if (defaultProjectId != null) settingsStore.setDefaultProjectId(defaultProjectId)
  }

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun `idle state without a default project shows a choose-project button`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))
    val viewModel = TimerViewModel(repository, settingsStore)

    composeRule.setContent {
      TimerScreen(
        viewModel = viewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }

    waitForText(string(R.string.timer_choose_project_button))
    composeRule.onNodeWithText(string(R.string.timer_choose_project_button)).assertIsEnabled()
  }

  @Test
  fun `idle state with a default project also shows a choose-project button`() {
    primeIdentity(defaultProjectId = PROJECT_ID)
    server.enqueue(MockResponse().setBody("[]"))
    val viewModel = TimerViewModel(repository, settingsStore)

    composeRule.setContent {
      TimerScreen(
        viewModel = viewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }

    waitForText(string(R.string.timer_start_button))
    composeRule.onNodeWithText(string(R.string.timer_choose_project_button)).assertIsEnabled()
  }

  @Test
  fun `running state shows the live elapsed time and a stop button`() {
    primeIdentity()
    val start = Instant.parse("2026-07-31T09:00:00Z")
    server.enqueue(
      MockResponse()
        .setBody(
          """[{"id": "e1", "projectId": "$PROJECT_ID", "timeInterval": {"start": "$start"}}]"""
        )
    )
    server.enqueue(MockResponse().setBody("[]")) // project-name lookup, unresolved here
    // A fixed clock (not tied to test wall time) keeps the ticked display stable for assertion.
    val viewModel = TimerViewModel(repository, settingsStore, clock = { start.plusSeconds(5) })

    composeRule.setContent {
      TimerScreen(
        viewModel = viewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }

    waitForText("00:00:05")
    composeRule.onNodeWithText(string(R.string.timer_stop_button)).assertExists()
  }

  @Test
  fun `running state shows the resolved project name, not the raw id`() {
    primeIdentity()
    val start = Instant.parse("2026-07-31T09:00:00Z")
    server.enqueue(
      MockResponse()
        .setBody(
          """[{"id": "e1", "projectId": "$PROJECT_ID", "timeInterval": {"start": "$start"}}]"""
        )
    )
    server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "Website"}]"""))
    val viewModel = TimerViewModel(repository, settingsStore, clock = { start.plusSeconds(5) })

    composeRule.setContent {
      TimerScreen(
        viewModel = viewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }

    waitForText("Website")
    composeRule.onAllNodesWithText(PROJECT_ID).assertCountEquals(0)
  }

  @Test
  fun `unauthorized error state offers a button to Settings`() {
    primeIdentity()
    server.enqueue(MockResponse().setResponseCode(401))
    val viewModel = TimerViewModel(repository, settingsStore)

    composeRule.setContent {
      TimerScreen(
        viewModel = viewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }

    waitForText(string(R.string.error_unauthorized))
    composeRule.onNodeWithText(string(R.string.timer_go_to_settings_button)).assertExists()
  }
}
