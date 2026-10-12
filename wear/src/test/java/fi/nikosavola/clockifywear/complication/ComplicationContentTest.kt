package fi.nikosavola.clockifywear.complication

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.FakeApiKeyCipher
import fi.nikosavola.clockifywear.di.AppContainer
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
private const val API_KEY = "test-api-key"

private val NOW = Instant.parse("2026-08-05T14:30:00Z")

private fun runningEntryJson(): String =
  """[{"id": "entry-1", "projectId": "$PROJECT_ID", """ +
    """"timeInterval": {"start": "${NOW.minusSeconds(12 * 60)}"}}]"""

private fun projectsJson(): String =
  """[{"id": "$PROJECT_ID", "name": "$PROJECT_NAME", "color": "#1976D2"}]"""

/**
 * Covers what the complication shows in each state, including the count-up case where the system
 * ticks the elapsed text itself rather than this app re-rendering it.
 */
@RunWith(RobolectricTestRunner::class)
class ComplicationContentTest {
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
  fun `a running entry counts up from its start under the project's name`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody(runningEntryJson()))
    server.enqueue(MockResponse().setBody(projectsJson()))

    val data = data()

    assertTrue(data.text is TimeDifferenceComplicationText)
    assertEquals(PROJECT_NAME, data.title!!.getTextAt(resources(), NOW).toString())
    assertNotNull(data.tapAction)
  }

  @Test
  fun `an unresolved project falls back to its id rather than erroring`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody(runningEntryJson()))
    server.enqueue(MockResponse().setBody("[]"))

    assertEquals(PROJECT_ID, data().title!!.getTextAt(resources(), NOW).toString())
  }

  @Test
  fun `nothing running shows a plain idle label`() {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))

    val data = data()

    assertEquals(
      string(R.string.complication_idle_label),
      data.text!!.getTextAt(resources(), NOW).toString(),
    )
  }

  @Test
  fun `a fetch that fails for any reason shows the idle label`() {
    server.enqueue(MockResponse().setResponseCode(401))

    val data = data()

    assertEquals(
      string(R.string.complication_idle_label),
      data.text!!.getTextAt(resources(), NOW).toString(),
    )
  }

  @Test
  fun `a preview is offered only for the type this complication supports`() {
    assertNotNull(content().preview(ComplicationType.SHORT_TEXT))
    assertNull(content().preview(ComplicationType.LONG_TEXT))
  }

  private fun data(): ShortTextComplicationData =
    runBlocking { content().data(container.repository) } as ShortTextComplicationData

  private fun content() = ComplicationContent(ApplicationProvider.getApplicationContext())

  private fun resources() = ApplicationProvider.getApplicationContext<Context>().resources

  private fun primeIdentity() = runBlocking {
    container.settingsStore.setWorkspaceId(WORKSPACE_ID)
    container.settingsStore.setUserId(USER_ID)
    container.settingsStore.setApiKey(API_KEY)
  }

  private fun string(@StringRes resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
