package fi.nikosavola.clockifywear.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.clockifyJson
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import fi.nikosavola.clockifywear.data.api.dto.UserDto
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
private const val API_KEY = "test-api-key"
private const val EMAIL = "user@example.com"

private fun userJson(activeWorkspace: String? = null, email: String? = null): String =
  clockifyJson.encodeToString(
    UserDto.serializer(),
    UserDto(id = USER_ID, email = email, activeWorkspace = activeWorkspace),
  )

// See TimerViewModelTest's top comment: job.join() (a real suspension) is used instead of
// advanceUntilIdle() wherever the awaited work makes a real MockWebServer/DataStore round trip.
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
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

  @Test
  fun `starts SignedOut when no workspace is persisted`() =
    runTest(testDispatcher) {
      val viewModel = SettingsViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is SettingsUiState.SignedOut)
      assertNull((state as SettingsUiState.SignedOut).error)
    }

  @Test
  fun `starts SignedIn with the persisted workspace id`() =
    runTest(testDispatcher) {
      settingsStore.setWorkspaceId(WORKSPACE_ID)
      val viewModel = SettingsViewModel(repository, settingsStore)

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is SettingsUiState.SignedIn)
      assertEquals(WORKSPACE_ID, (state as SettingsUiState.SignedIn).workspaceId)
      assertNull(state.email)
    }

  @Test
  fun `signIn success surfaces SignedIn with the resolved workspace`() =
    runTest(testDispatcher) {
      server.enqueue(MockResponse().setBody(userJson(activeWorkspace = WORKSPACE_ID)))
      val viewModel = SettingsViewModel(repository, settingsStore)
      viewModel.load().join()

      viewModel.signIn(API_KEY).join()

      val state = viewModel.uiState.value
      assertTrue(state is SettingsUiState.SignedIn)
      assertEquals(WORKSPACE_ID, (state as SettingsUiState.SignedIn).workspaceId)
    }

  @Test
  fun `signIn success surfaces SignedIn with the persisted email`() =
    runTest(testDispatcher) {
      server.enqueue(
        MockResponse().setBody(userJson(activeWorkspace = WORKSPACE_ID, email = EMAIL))
      )
      val viewModel = SettingsViewModel(repository, settingsStore)
      viewModel.load().join()

      viewModel.signIn(API_KEY).join()

      val state = viewModel.uiState.value
      assertTrue(state is SettingsUiState.SignedIn)
      assertEquals(EMAIL, (state as SettingsUiState.SignedIn).email)
    }

  @Test
  fun `signIn failure surfaces SignedOut carrying the error`() =
    runTest(testDispatcher) {
      server.enqueue(MockResponse().setResponseCode(401))
      val viewModel = SettingsViewModel(repository, settingsStore)
      viewModel.load().join()

      viewModel.signIn(API_KEY).join()

      val state = viewModel.uiState.value
      assertTrue(state is SettingsUiState.SignedOut)
      assertEquals(ClockifyError.Unauthorized, (state as SettingsUiState.SignedOut).error)
    }

  @Test
  fun `signOut clears settings and returns to SignedOut`() =
    runTest(testDispatcher) {
      settingsStore.setWorkspaceId(WORKSPACE_ID)
      settingsStore.setApiKey(API_KEY)
      val viewModel = SettingsViewModel(repository, settingsStore)
      viewModel.load().join()
      assertTrue(viewModel.uiState.value is SettingsUiState.SignedIn)

      viewModel.signOut().join()

      val state = viewModel.uiState.value
      assertTrue(state is SettingsUiState.SignedOut)
      assertNull(settingsStore.currentSettings().apiKey)
    }

  @Test
  fun `signOut invokes onSignedOut exactly once`() =
    runTest(testDispatcher) {
      settingsStore.setWorkspaceId(WORKSPACE_ID)
      var signedOutCount = 0
      val viewModel =
        SettingsViewModel(repository, settingsStore, onSignedOut = { signedOutCount++ })
      viewModel.load().join()

      viewModel.signOut().join()

      assertEquals(1, signedOutCount)
    }
}
