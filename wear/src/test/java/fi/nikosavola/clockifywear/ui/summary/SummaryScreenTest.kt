package fi.nikosavola.clockifywear.ui.summary

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.GraphicsMode

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val PROJECT_NAME = "Website redesign"
private const val API_KEY = "test-api-key"
private const val WAIT_TIMEOUT_MS = 5_000L
private const val SETTINGS_MARKER = "settings-marker"

// A Wednesday - see SummaryBoundariesTest/SummaryViewModelTest for why the default locale matters.
private val NOW = Instant.parse("2026-08-05T14:30:00Z")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SummaryScreenTest {
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

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun viewModel(): SummaryViewModel =
    SummaryViewModel(repository, clock = { NOW }, zoneId = ZoneOffset.UTC)

  @Test
  fun `a loaded today entry shows its project name and the section's summed total`() {
    primeIdentity()
    server.enqueue(
      MockResponse()
        .setBody(
          """[{"id": "e1", "projectId": "$PROJECT_ID", """ +
            """"timeInterval": {"start": "2026-08-05T10:00:00Z", "end": "2026-08-05T10:24:00Z"}},""" +
            """{"id": "e2", "projectId": "$PROJECT_ID", """ +
            """"timeInterval": {"start": "2026-08-05T08:00:00Z", "end": "2026-08-05T09:00:00Z"}}]"""
        )
    )
    server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ID", "name": "$PROJECT_NAME"}]"""))
    val viewModel = viewModel()

    composeRule.setContent { SummaryScreen(viewModel = viewModel, onNavigateToSettings = {}) }

    waitForText(string(R.string.summary_section_today))
    composeRule.onAllNodesWithText(PROJECT_NAME).assertCountEquals(2)
    // Distinct from either entry's own duration (24min, 1h), so this can only be the section total.
    waitForText("1h 24min")
  }

  @Test
  fun `all-empty sections show the empty-state message`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))
    val viewModel = viewModel()

    composeRule.setContent { SummaryScreen(viewModel = viewModel, onNavigateToSettings = {}) }

    waitForText(string(R.string.summary_section_empty))
    // One per section: Today, This week, Last week.
    composeRule.onAllNodesWithText(string(R.string.summary_section_empty)).assertCountEquals(3)
    // No projects()/cache lookup for an empty range - only the timeEntriesBetween request happened.
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `an unauthorized error offers a button that navigates to Settings`() {
    primeIdentity()
    server.enqueue(MockResponse().setResponseCode(401))
    val viewModel = viewModel()
    var navigatedToSettings by mutableStateOf(false)

    composeRule.setContent {
      SummaryScreen(viewModel = viewModel, onNavigateToSettings = { navigatedToSettings = true })
      if (navigatedToSettings) Text(text = SETTINGS_MARKER)
    }

    waitForText(string(R.string.error_unauthorized))
    composeRule.onNodeWithText(string(R.string.timer_go_to_settings_button)).performClick()

    waitForText(SETTINGS_MARKER)
    assertTrue(navigatedToSettings)
  }
}
