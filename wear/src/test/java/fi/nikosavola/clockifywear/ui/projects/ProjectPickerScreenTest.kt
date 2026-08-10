package fi.nikosavola.clockifywear.ui.projects

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val PROJECT_NAME = "Website redesign"
private const val API_KEY = "test-api-key"
private const val WAIT_TIMEOUT_MS = 5_000L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectPickerScreenTest {
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

  private fun primeIdentity() = runBlocking { settingsStore.setWorkspaceId(WORKSPACE_ID) }

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun `tapping a project invokes the selection callback with its id`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "$PROJECT_NAME"}]"""))
    val viewModel = ProjectPickerViewModel(repository, settingsStore)
    var selectedProjectId: String? = null

    composeRule.setContent {
      ProjectPickerScreen(
        viewModel = viewModel,
        onProjectSelected = { selectedProjectId = it },
        onNavigateToSettings = {},
      )
    }

    waitForText(PROJECT_NAME)
    composeRule.onNodeWithText(PROJECT_NAME).performClick()

    assertEquals(PROJECT_ID, selectedProjectId)
  }

  @Test
  fun `an empty project list shows the empty-state message`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))
    val viewModel = ProjectPickerViewModel(repository, settingsStore)

    composeRule.setContent {
      ProjectPickerScreen(viewModel = viewModel, onProjectSelected = {}, onNavigateToSettings = {})
    }

    waitForText(string(R.string.project_picker_empty))
  }
}
