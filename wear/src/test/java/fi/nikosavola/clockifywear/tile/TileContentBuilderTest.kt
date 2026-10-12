package fi.nikosavola.clockifywear.tile

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.tiles.TileBuilders
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.FakeApiKeyCipher
import fi.nikosavola.clockifywear.di.AppContainer
import java.time.Duration
import java.time.Instant
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

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val PROJECT_NAME = "Website redesign"
private const val API_KEY = "test-api-key"
private const val OPEN_APP_CLICK_ID = "open_clockify"

private val NOW = Instant.parse("2026-08-05T14:30:00Z")
private val ONE_MINUTE_MILLIS = Duration.ofMinutes(1).toMillis()
private val THIRTY_MINUTES_MILLIS = Duration.ofMinutes(30).toMillis()

private fun runningEntryJson(): String =
  """[{"id": "entry-1", "projectId": "$PROJECT_ID", """ +
    """"timeInterval": {"start": "${NOW.minusSeconds(12 * 60)}"}}]"""

// What the stop endpoint answers with: one entry rather than a list, unlike the in-progress read.
private fun stoppedEntryJson(): String =
  """{"id": "entry-1", "projectId": "$PROJECT_ID", """ +
    """"timeInterval": {"start": "${NOW.minusSeconds(12 * 60)}", "end": "$NOW"}}"""

// A short name for the layout element type, whose own name is long enough that ktfmt and ktlint
// disagree about how to wrap a signature that uses it.
private typealias TileElement = LayoutElementBuilders.LayoutElement

private fun projectsJson(): String =
  """[{"id": "$PROJECT_ID", "name": "$PROJECT_NAME", "color": "#1976D2"}]"""

/**
 * Covers the state-to-tile mapping against a MockWebServer-backed [AppContainer]: which text ends
 * up on the surface, which clickable the edge button gets, and how often the renderer is told to
 * come back. Click *resolution* itself is [TileClickResolverTest]'s subject.
 */
@RunWith(RobolectricTestRunner::class)
class TileContentBuilderTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var container: AppContainer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    container =
      AppContainer(
        ApplicationProvider.getApplicationContext(),
        baseUrl = server.url("/").toString(),
        dataStore =
          PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("settings.preferences_pb") }
          ),
        apiKeyCipher = FakeApiKeyCipher(),
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `a running entry shows the project and offers a stop`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody(runningEntryJson()))
    server.enqueue(MockResponse().setBody(projectsJson()))

    val tile = buildTile()

    assertEquals(ONE_MINUTE_MILLIS, tile.freshnessIntervalMillis)
    assertTrue(textsOf(tile).contains(PROJECT_NAME))
    assertTrue(clickableIdsOf(tile).any { it.startsWith("stop_timer:") })
  }

  @Test
  fun `nothing running with a default project offers a start and refreshes lazily`() {
    primeIdentity(defaultProjectId = PROJECT_ID)
    server.enqueue(MockResponse().setBody("[]"))

    val tile = buildTile()

    assertEquals(THIRTY_MINUTES_MILLIS, tile.freshnessIntervalMillis)
    assertTrue(textsOf(tile).contains(string(R.string.tile_no_timer_running)))
    assertTrue(clickableIdsOf(tile).any { it.startsWith("start_timer:") })
  }

  @Test
  fun `nothing running and no default project falls back to opening the app`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))

    val tile = buildTile()

    val clickIds = clickableIdsOf(tile)
    assertTrue(textsOf(tile).contains(string(R.string.tile_no_timer_running)))
    assertTrue(clickIds.isNotEmpty())
    assertTrue(clickIds.all { it == OPEN_APP_CLICK_ID })
  }

  @Test
  fun `a signed-out state says so and opens the app rather than offering a start`() {
    server.enqueue(MockResponse().setResponseCode(401))

    val tile = buildTile()

    val clickIds = clickableIdsOf(tile)
    assertTrue(textsOf(tile).contains(string(R.string.tile_sign_in_required)))
    assertTrue(clickIds.isNotEmpty())
    assertTrue(clickIds.all { it == OPEN_APP_CLICK_ID })
  }

  // The seam the extraction adds: the click id the host reports has to reach the resolver, or
  // tapping the tile's edge button on the watch does nothing. Every other test here passes null.
  @Test
  fun `a stop click from the host stops the timer rather than re-reading its state`() {
    primeIdentity(defaultProjectId = PROJECT_ID)
    server.enqueue(MockResponse().setBody(stoppedEntryJson()))
    // Queued behind the stop response, so a build that dropped the click instead of acting on it
    // fails on the running project still showing, not on an empty response queue.
    server.enqueue(MockResponse().setBody(runningEntryJson()))

    val tile = buildTile(lastClickableId = "stop_timer:tok")

    assertEquals("PATCH", server.takeRequest().method)
    assertTrue(textsOf(tile).contains(string(R.string.tile_no_timer_running)))
    assertEquals(
      "tok",
      runBlocking { container.settingsStore.currentSettings().lastConsumedTileClickToken },
    )
  }

  @Test
  fun `a failed status check retries on the running cadence`() {
    primeIdentity(defaultProjectId = PROJECT_ID)
    server.enqueue(MockResponse().setResponseCode(500))

    val tile = buildTile()

    assertEquals(ONE_MINUTE_MILLIS, tile.freshnessIntervalMillis)
    assertTrue(textsOf(tile).contains(string(R.string.tile_status_unknown)))
  }

  @Test
  fun `resources carry a version and both edge button icons`() {
    val resources = builder().resources()

    assertEquals("2", resources.version)
    assertEquals(setOf(PLAY_ICON_ID, PAUSE_ICON_ID), resources.idToImageMapping.keys)
  }

  private fun buildTile(lastClickableId: String? = null): TileBuilders.Tile = runBlocking {
    builder()
      .build(
        deviceConfiguration = DeviceParametersBuilders.DeviceParameters.Builder().build(),
        lastClickableId = lastClickableId,
        repository = container.repository,
        settingsStore = container.settingsStore,
      )
  }

  private fun builder() = TileContentBuilder(ApplicationProvider.getApplicationContext())

  private fun primeIdentity(defaultProjectId: String? = null) = runBlocking {
    container.settingsStore.setWorkspaceId(WORKSPACE_ID)
    container.settingsStore.setUserId(USER_ID)
    container.settingsStore.setApiKey(API_KEY)
    if (defaultProjectId != null) {
      container.settingsStore.setDefaultProjectId(defaultProjectId)
    }
  }

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  // Nullable on the proto-derived getters; a tile that omits any of these is not one this app
  // built.
  private fun rootOf(tile: TileBuilders.Tile): TileElement {
    val timeline = requireNotNull(tile.tileTimeline) { "tile has no timeline" }
    val layout = requireNotNull(timeline.timelineEntries.first().layout) { "tile has no layout" }
    return requireNotNull(layout.root) { "tile layout has no root" }
  }

  private fun textsOf(tile: TileBuilders.Tile): List<String> =
    rootOf(tile).descendants().mapNotNull { element ->
      (element as? LayoutElementBuilders.Text)?.text?.value
    }

  private fun clickableIdsOf(tile: TileBuilders.Tile): List<String> =
    rootOf(tile).descendants().mapNotNull { element ->
      when (element) {
        is LayoutElementBuilders.Text -> element.modifiers?.clickable?.id
        is LayoutElementBuilders.Column -> element.modifiers?.clickable?.id
        is LayoutElementBuilders.Row -> element.modifiers?.clickable?.id
        is LayoutElementBuilders.Box -> element.modifiers?.clickable?.id
        is LayoutElementBuilders.Spacer -> element.modifiers?.clickable?.id
        else -> null
      }
    }

  // Walks the tree rather than searching a serialised form: the element types that can hold
  // children
  // are a closed set, so a text or clickable that vanishes from the tree fails the test.
  private fun TileElement.descendants(): List<TileElement> =
    listOf(this) +
      when (this) {
        is LayoutElementBuilders.Column -> contents.flatMap { it.descendants() }
        is LayoutElementBuilders.Row -> contents.flatMap { it.descendants() }
        is LayoutElementBuilders.Box -> contents.flatMap { it.descendants() }
        else -> emptyList()
      }
}
