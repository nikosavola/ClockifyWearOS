package fi.nikosavola.clockifywear.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.MaterialTheme
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import fi.nikosavola.clockifywear.ui.projects.ProjectPickerScreen
import fi.nikosavola.clockifywear.ui.projects.ProjectPickerViewModel
import fi.nikosavola.clockifywear.ui.recents.RecentsScreen
import fi.nikosavola.clockifywear.ui.recents.RecentsViewModel
import fi.nikosavola.clockifywear.ui.settings.SettingsScreen
import fi.nikosavola.clockifywear.ui.settings.SettingsViewModel
import fi.nikosavola.clockifywear.ui.summary.SummaryScreen
import fi.nikosavola.clockifywear.ui.summary.SummaryViewModel
import fi.nikosavola.clockifywear.ui.tasks.TaskPickerScreen
import fi.nikosavola.clockifywear.ui.tasks.TaskPickerViewModel
import fi.nikosavola.clockifywear.ui.theme.ClockifyTheme
import fi.nikosavola.clockifywear.ui.timer.TimerScreen
import fi.nikosavola.clockifywear.ui.timer.TimerViewModel
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val OTHER_PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d21"
private const val PROJECT_NAME = "Website redesign"
private const val OTHER_PROJECT_NAME = "Client onboarding"
private const val TASK_ID = "5f8a1b2c3d4e5f6a7b8c9d30"
private const val TASK_NAME = "Copy edits"
private const val API_KEY = "test-api-key"
private const val WAIT_TIMEOUT_MS = 5_000L

// Pinned so every run produces byte-identical fixtures: the elapsed readout is derived from the
// clock, and a screenshot that changes with the wall clock is not a reference image.
private val NOW = Instant.parse("2026-08-05T14:30:00Z")
private const val RUNNING_SINCE_SECONDS = 720L

// Matches the device captures already in docs/screenshots: 240dp at xhdpi is 480px square. -night
// because a Wear screen is dark; without it the host's light window background shows through.
private const val QUALIFIERS = "w240dp-h240dp-round-night-xhdpi"
private const val SCREENSHOT_SIZE = 480
private const val SCREENSHOT_DIR = "build/screenshots"

// A blank frame still encodes as a valid PNG, so the only way a broken render shows up in CI is by
// counting what is in it. A flat background measures under 10 distinct colours; these screens all
// measure 500 and up, and their most common colour covers at most 0.87 of the frame. Both bounds
// sit well clear of those, so a font or Skia change moves them without failing the build.
private const val MIN_DISTINCT_COLOURS = 100
private const val MAX_MODAL_COLOUR_SHARE = 0.95

private fun projectsJson(): String =
  """[{"id": "$PROJECT_ID", "name": "$PROJECT_NAME", "color": "#1976D2"},""" +
    """{"id": "$OTHER_PROJECT_ID", "name": "$OTHER_PROJECT_NAME", "color": "#F4511E"}]"""

private fun runningEntryJson(): String =
  """[{"id": "entry-1", "projectId": "$PROJECT_ID", """ +
    """"timeInterval": {"start": "${NOW.minusSeconds(RUNNING_SINCE_SECONDS)}"}}]"""

private fun timeEntriesJson(count: Int, minutesEach: Long, description: String): String =
  (0 until count).joinToString(prefix = "[", postfix = "]") { index ->
    val start = NOW.minusSeconds(minutesEach * 60 * (index + 1))
    """{"id": "entry-$index", "projectId": "$PROJECT_ID", "description": "$description", """ +
      """"timeInterval": {"start": "$start", "end": "${start.plusSeconds(minutesEach * 60)}"}}"""
  }

private fun tasksJson(): String = """[{"id": "$TASK_ID", "name": "$TASK_NAME"}]"""

/**
 * Renders each screen to a PNG under `wear/build/screenshots`, which `just screenshots` copies into
 * `docs/screenshots`. Screens are rasterised by drawing the activity's view into a bitmap rather
 * than with `captureToImage`, which waits for a real draw pass the Robolectric looper never
 * delivers.
 *
 * These are reference images of the real composables, but not of a real device: no round screen
 * mask, and the host's font rasteriser rather than the watch's. The tile screens have no equivalent
 * here at all, since a `Tile` is protolayout built for the system renderer, so `docs/screenshots`
 * keeps its two hand-captured tile images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = QUALIFIERS)
class ScreenScreenshotsTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var settingsStore: SettingsStore
  private lateinit var repository: ClockifyRepository

  // Built per test rather than per composition: lint rejects constructing a view model inside a
  // composable, and every screen wants a fresh one anyway.
  private val timerViewModel: TimerViewModel
    get() = TimerViewModel(repository, settingsStore, clock = { NOW })

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
    repository =
      ClockifyRepository(api, settingsStore, ProjectCache(File(tempFolder.root, "projects.json")))
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `timer screen with no timer running`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))
    setScreen {
      TimerScreen(
        viewModel = timerViewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }
    waitForContentDescription(R.string.timer_choose_project_button)

    capture("timer_idle")
  }

  @Test
  fun `timer screen with a timer running`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody(runningEntryJson()))
    server.enqueue(MockResponse().setBody(projectsJson()))
    setScreen {
      TimerScreen(
        viewModel = timerViewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }
    waitForContentDescription(R.string.timer_stop_button)

    capture("timer_running")
  }

  @Test
  fun `timer screen signed out`() {
    server.enqueue(MockResponse().setResponseCode(401))
    setScreen {
      TimerScreen(
        viewModel = timerViewModel,
        onNavigateToSettings = {},
        onNavigateToProjectPicker = {},
        onNavigateToRecents = {},
      )
    }
    waitForText(R.string.error_not_signed_in)

    capture("timer_signed_out")
  }

  @Test
  fun `the project picker lists the workspace's projects`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody(projectsJson()))
    val viewModel = ProjectPickerViewModel(repository, settingsStore)
    setScreen {
      ProjectPickerScreen(viewModel = viewModel, onProjectSelected = {}, onNavigateToSettings = {})
    }
    waitForTextNow(PROJECT_NAME)

    capture("project_picker")
  }

  @Test
  fun `the task picker lists a project's tasks`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody(tasksJson()))
    // The header resolves the project's name once the tasks have loaded.
    server.enqueue(MockResponse().setBody(projectsJson()))
    val viewModel = TaskPickerViewModel(repository, settingsStore, projectId = PROJECT_ID)
    setScreen { TaskPickerScreen(viewModel = viewModel, onStarted = {}, onNavigateToSettings = {}) }
    waitForTextNow(TASK_NAME)

    capture("task_picker")
  }

  @Test
  fun `the recents list shows the last entries`() {
    primeIdentity()
    server.enqueue(
      MockResponse().setBody(timeEntriesJson(3, minutesEach = 30, description = "Copy edits"))
    )
    server.enqueue(MockResponse().setBody(projectsJson()))
    val viewModel = RecentsViewModel(repository, settingsStore)
    setScreen { RecentsScreen(viewModel = viewModel, onStarted = {}, onNavigateToSettings = {}) }
    waitForTextNow(PROJECT_NAME)

    capture("recents")
  }

  @Test
  fun `the summary groups entries by day and week`() {
    primeIdentity()
    server.enqueue(
      MockResponse().setBody(timeEntriesJson(4, minutesEach = 45, description = "Copy edits"))
    )
    server.enqueue(MockResponse().setBody(projectsJson()))
    val viewModel = SummaryViewModel(repository, clock = { NOW }, zoneId = ZoneOffset.UTC)
    setScreen { SummaryScreen(viewModel = viewModel, onNavigateToSettings = {}) }
    waitForText(R.string.summary_section_today)

    capture("summary")
  }

  @Test
  fun `settings with no key entered`() {
    val viewModel = SettingsViewModel(repository, settingsStore)
    setScreen { SettingsScreen(viewModel = viewModel) }
    waitForText(R.string.settings_api_key_label)

    capture("settings")
  }

  private fun primeIdentity() = runBlocking {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
  }

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  private fun setScreen(content: @Composable () -> Unit) {
    composeRule.setContent {
      ClockifyTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
      }
    }
  }

  private fun waitForText(@StringRes resId: Int) = waitForTextNow(string(resId))

  private fun waitForContentDescription(@StringRes resId: Int) {
    val description = string(resId)
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForTextNow(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun capture(name: String) {
    composeRule.waitForIdle()
    val view = composeRule.activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))

    File(SCREENSHOT_DIR).mkdirs()
    File(SCREENSHOT_DIR, "$name.png").outputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }

    assertEquals("$name rendered at an unexpected width", SCREENSHOT_SIZE, bitmap.width)
    assertEquals("$name rendered at an unexpected height", SCREENSHOT_SIZE, bitmap.height)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val counts = mutableMapOf<Int, Int>()
    for (pixel in pixels) counts[pixel] = (counts[pixel] ?: 0) + 1
    val modalShare = counts.values.max().toDouble() / pixels.size
    assertTrue(
      "$name looks blank: only ${counts.size} distinct colours",
      counts.size > MIN_DISTINCT_COLOURS,
    )
    assertTrue("$name is one flat colour", modalShare < MAX_MODAL_COLOUR_SHARE)
  }
}
