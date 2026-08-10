package fi.nikosavola.clockifywear.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val API_KEY = "test-api-key"
private const val WAIT_TIMEOUT_MS = 5_000L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
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

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun `signed-out screen shows the api key field and a disabled sign-in button`() {
    val viewModel = SettingsViewModel(repository, settingsStore)

    composeRule.setContent { SettingsScreen(viewModel = viewModel) }

    waitForText(string(R.string.settings_api_key_label))
    composeRule.onNodeWithText(string(R.string.settings_sign_in_button)).assertIsNotEnabled()
  }

  @Test
  fun `entering an api key enables the sign-in button`() {
    val viewModel = SettingsViewModel(repository, settingsStore)
    composeRule.setContent { SettingsScreen(viewModel = viewModel) }
    waitForText(string(R.string.settings_api_key_label))

    composeRule.onNode(hasSetTextAction()).performTextInput("secret-key")

    composeRule.onNodeWithText(string(R.string.settings_sign_in_button)).assertIsEnabled()
  }

  @Test
  fun `paste button fills the field from the system clipboard`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val systemClipboard = context.getSystemService(ClipboardManager::class.java)
    systemClipboard.setPrimaryClip(ClipData.newPlainText("api key", "clipboard-key"))
    val viewModel = SettingsViewModel(repository, settingsStore)
    composeRule.setContent { SettingsScreen(viewModel = viewModel) }
    waitForText(string(R.string.settings_api_key_label))

    composeRule.onNodeWithText(string(R.string.settings_paste_button)).performClick()

    composeRule.onNode(hasSetTextAction()).assertTextContains("clipboard-key")
  }
}
