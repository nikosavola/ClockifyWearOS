package fi.nikosavola.clockifywear.ui.summary

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
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

// A Wednesday, with the default locale fixed to Monday-first below - see summaryBoundaries.
private val NOW = Instant.parse("2026-08-05T14:30:00Z")

private const val NEVER_PRIMED_WAIT_MS = 300L

private fun timeEntryJson(id: String, projectId: String?, start: String, end: String): String {
  val fields = mutableListOf(""""id": "$id"""")
  if (projectId != null) fields += """"projectId": "$projectId""""
  fields += """"timeInterval": {"start": "$start", "end": "$end"}"""
  return fields.joinToString(prefix = "{", postfix = "}")
}

private fun entriesListJson(vararg entries: String): String =
  entries.joinToString(prefix = "[", postfix = "]")

@RunWith(RobolectricTestRunner::class)
class SummaryViewModelTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var originalLocale: Locale
  private lateinit var server: MockWebServer
  private lateinit var settingsStore: SettingsStore
  private lateinit var repository: ClockifyRepository

  @Before
  fun setUp() {
    originalLocale = Locale.getDefault()
    // Monday-first, matching NOW's boundaries below.
    Locale.setDefault(Locale.Builder().setLanguage("fi").setRegion("FI").build())
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
    Locale.setDefault(originalLocale)
  }

  private suspend fun primeIdentity() {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
  }

  private fun viewModel(): SummaryViewModel =
    SummaryViewModel(repository, clock = { NOW }, zoneId = ZoneOffset.UTC)

  @Test
  fun `load buckets entries into today, this week, and last week, dropping older ones`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse()
          .setBody(
            entriesListJson(
              timeEntryJson(
                "today",
                PROJECT_ALPHA_ID,
                start = "2026-08-05T10:00:00Z",
                end = "2026-08-05T11:30:00Z",
              ),
              timeEntryJson(
                "this-week",
                PROJECT_ALPHA_ID,
                start = "2026-08-04T09:00:00Z",
                end = "2026-08-04T10:00:00Z",
              ),
              timeEntryJson(
                "last-week",
                PROJECT_ALPHA_ID,
                start = "2026-07-28T09:00:00Z",
                end = "2026-07-28T09:45:00Z",
              ),
              // Before last week's start boundary: must not land in any section, even though the
              // server response (mocked here) does not itself filter by the requested range.
              timeEntryJson(
                "older",
                PROJECT_ALPHA_ID,
                start = "2026-07-20T09:00:00Z",
                end = "2026-07-20T10:00:00Z",
              ),
            )
          )
      )
      server.enqueue(MockResponse().setBody("""[{"id": "$PROJECT_ALPHA_ID", "name": "Alpha"}]"""))
      val viewModel = viewModel()

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is SummaryUiState.Loaded)
      val loaded = state as SummaryUiState.Loaded
      assertEquals(listOf("today"), loaded.today.entries.map { it.id })
      assertEquals(5400L, loaded.today.totalSeconds)
      assertEquals(listOf("this-week"), loaded.thisWeek.entries.map { it.id })
      assertEquals(3600L, loaded.thisWeek.totalSeconds)
      assertEquals(listOf("last-week"), loaded.lastWeek.entries.map { it.id })
      assertEquals(2700L, loaded.lastWeek.totalSeconds)
    }

  @Test
  fun `load falls back to the raw project id, tolerating a failed projects lookup as empty`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(
        MockResponse()
          .setBody(
            entriesListJson(
              timeEntryJson(
                "today",
                PROJECT_ALPHA_ID,
                start = "2026-08-05T10:00:00Z",
                end = "2026-08-05T11:00:00Z",
              )
            )
          )
      )
      server.enqueue(MockResponse().setResponseCode(500)) // projects() lookup fails
      val viewModel = viewModel()

      viewModel.load().join()

      val loaded = viewModel.uiState.value as SummaryUiState.Loaded
      // Same convention as RecentsViewModel: an unresolved-but-real project id is not the same as
      // no project at all, so this falls back to the raw id rather than the "No project" label.
      assertEquals(PROJECT_ALPHA_ID, loaded.today.entries.single().projectName)
    }

  @Test
  fun `load surfaces the error state on a failed timeEntriesBetween call`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(401))
      val viewModel = viewModel()

      viewModel.load().join()

      val state = viewModel.uiState.value
      assertTrue(state is SummaryUiState.Error)
      assertEquals(ClockifyError.Unauthorized, (state as SummaryUiState.Error).error)
    }

  // Guards against overlapping loads racing (e.g. a quick background/foreground cycle firing a
  // second load while the first is still in flight) landing out of order.
  @Test
  fun `load cancels any previous in-flight load before starting a new one`() =
    runTest(testDispatcher) {
      primeIdentity()
      server.enqueue(MockResponse().setBody("[]"))
      val viewModel = viewModel()

      val firstJob = viewModel.load()
      val secondJob = viewModel.load()
      secondJob.join()

      assertTrue(firstJob.isCancelled)
      assertTrue(viewModel.uiState.value is SummaryUiState.Loaded)
    }

  // Same reasoning as RecentsViewModelTest's equivalent test.
  @Test
  fun `load awaits settingsPrimed before making any repository call`() =
    runTest(testDispatcher) {
      primeIdentity()
      val settingsPrimed = CompletableDeferred<Settings>()
      val viewModel =
        SummaryViewModel(repository, settingsPrimed, clock = { NOW }, zoneId = ZoneOffset.UTC)

      val job = viewModel.load()
      val completed =
        withContext(Dispatchers.Default) { withTimeoutOrNull(NEVER_PRIMED_WAIT_MS) { job.join() } }

      assertNull(completed)
      assertEquals(0, server.requestCount)
      assertTrue(job.isActive)
      assertTrue(viewModel.uiState.value is SummaryUiState.Loading)

      server.enqueue(MockResponse().setBody("[]"))
      settingsPrimed.complete(Settings())
      job.join()

      assertTrue(viewModel.uiState.value is SummaryUiState.Loaded)
    }
}
