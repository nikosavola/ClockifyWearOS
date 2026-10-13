package fi.nikosavola.clockifywear.appfunctions

import android.content.Intent
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionLimitExceededException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSystemUnknownException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.FakeApiKeyCipher
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.ClockifyApi
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val API_KEY = "test-api-key"
private const val SERVICE_NAME =
  "fi.nikosavola.clockifywear.appfunctions.ClockifyAppFunctionService"

private val FIXED_NOW = Instant.parse("2026-07-31T09:05:00Z")

private fun timeEntryJson(
  id: String = "entry-1",
  projectId: String? = PROJECT_ID,
  start: String = "2026-07-31T09:00:00Z",
  end: String? = null,
): String {
  val project = projectId?.let { "\"$it\"" } ?: "null"
  val endValue = end?.let { "\"$it\"" } ?: "null"
  return """{"id": "$id", "projectId": $project, "timeInterval": {"start": "$start", "end": $endValue}}"""
}

private fun projectsPageJson() =
  """[{"id": "$PROJECT_ID", "name": "Website", "clientName": "Acme", "archived": false}]"""

@Config(sdk = [36])
@RunWith(RobolectricTestRunner::class)
class ClockifyAppFunctionsTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var api: ClockifyApi
  private lateinit var settingsStore: SettingsStore
  private lateinit var repository: ClockifyRepository
  private lateinit var appFunctions: ClockifyAppFunctions

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    api = createClockifyApi(apiKey = { API_KEY }, baseUrl = server.url("/").toString())
    settingsStore =
      SettingsStore(
        PreferenceDataStoreFactory.create(
          produceFile = { tempFolder.newFile("settings.preferences_pb") }
        ),
        FakeApiKeyCipher(),
      )
    repository =
      ClockifyRepository(api, settingsStore, ProjectCache(File(tempFolder.root, "projects.json"))) {
        FIXED_NOW
      }
    appFunctions = clockifyAppFunctions()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  // Unconfined so the withContext(ioDispatcher) in every entry point runs inline rather than
  // needing the real IO thread pool; the network underneath is still real.
  private fun clockifyAppFunctions() =
    ClockifyAppFunctions(repository, { FIXED_NOW }, UnconfinedTestDispatcher())

  private suspend fun signIn() {
    settingsStore.setWorkspaceId(WORKSPACE_ID)
    settingsStore.setUserId(USER_ID)
    appFunctions = clockifyAppFunctions()
  }

  private suspend fun appFunctionFailure(block: suspend () -> Unit): AppFunctionException {
    try {
      block()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (exception: AppFunctionException) {
      return exception
    }
    throw AssertionError("expected an AppFunctionException, but the call succeeded")
  }

  // --- wiring -----------------------------------------------------------------------------------

  // The one thing unit tests over the logic cannot cover: if the generated service name and the
  // manifest entry disagree, the app installs and the service is simply never bound, with no crash
  // and no other symptom.
  @Test
  fun `the generated service is registered under the name the manifest declares`() {
    val resolved =
      ApplicationProvider.getApplicationContext<android.content.Context>()
        .packageManager
        .resolveService(Intent("android.app.appfunctions.AppFunctionService"), 0)

    assertNotNull("no service registered for the AppFunctionService action", resolved)
    assertEquals(SERVICE_NAME, resolved!!.serviceInfo.name)
    assertEquals("android.permission.BIND_APP_FUNCTION_SERVICE", resolved.serviceInfo.permission)
    // And the class really exists under that name, generated by KSP from the abstract base.
    assertTrue(
      BaseClockifyAppFunctionService::class.java.isAssignableFrom(Class.forName(SERVICE_NAME))
    )
  }

  // --- error mapping ----------------------------------------------------------------------------

  @Test
  fun `not being signed in asks the user to sign in rather than reporting a generic failure`() =
    runTest {
      val failure = appFunctionFailure { appFunctions.runningTimer() }

      assertTrue(failure is AppFunctionPermissionRequiredException)
      assertTrue(failure.message.orEmpty().contains("not signed in"))
    }

  @Test
  fun `a rejected API key surfaces as permission required, not as an app failure`() = runTest {
    signIn()
    server.enqueue(MockResponse().setResponseCode(401))

    val failure = appFunctionFailure { appFunctions.runningTimer() }

    assertTrue(failure is AppFunctionPermissionRequiredException)
    assertTrue(failure.message.orEmpty().contains("rejected"))
  }

  @Test
  fun `rate limiting is reported as a limit, not as an unspecified app failure`() = runTest {
    signIn()
    // Two, because the client retries a 429 once before giving up. Retry-After: 0 keeps the retry
    // from actually sleeping through the test.
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))

    val failure = appFunctionFailure { appFunctions.runningTimer() }

    assertTrue(failure is AppFunctionLimitExceededException)
  }

  @Test
  fun `an unreachable server is reported as a system failure, not as this app breaking`() =
    runTest {
      signIn()
      server.shutdown()

      val failure = appFunctionFailure { appFunctions.runningTimer() }

      assertTrue(failure is AppFunctionSystemUnknownException)
    }

  @Test
  fun `a server error is reported as this app failing, since the agent cannot act on it`() =
    runTest {
      signIn()
      server.enqueue(MockResponse().setResponseCode(500))

      val failure = appFunctionFailure { appFunctions.runningTimer() }

      assertTrue(failure is AppFunctionAppUnknownException)
      assertTrue(failure.message.orEmpty().contains("500"))
    }

  // --- running timer ----------------------------------------------------------------------------

  @Test
  fun `a running timer reports its project and how long it has been running`() = runTest {
    signIn()
    server.enqueue(MockResponse().setBody("[${timeEntryJson()}]"))
    server.enqueue(MockResponse().setBody(projectsPageJson()))

    val timer = appFunctions.runningTimer()

    assertTrue(timer.isRunning)
    assertEquals("Website", timer.projectName)
    assertEquals(5L, timer.elapsedMinutes)
  }

  @Test
  fun `no running timer reports not running rather than failing`() = runTest {
    signIn()
    server.enqueue(MockResponse().setBody("[]"))

    val timer = appFunctions.runningTimer()

    assertFalse(timer.isRunning)
    assertEquals(null, timer.projectName)
  }

  @Test
  fun `an ended entry from the in-progress query reports not running and carries nothing else`() =
    runTest {
      signIn()
      server.enqueue(
        MockResponse()
          .setBody(
            "[${timeEntryJson(start = "2026-07-31T08:00:00Z", end = "2026-07-31T08:30:00Z")}]"
          )
      )

      val timer = appFunctions.runningTimer()

      assertFalse(timer.isRunning)
      assertNull(timer.projectName)
      assertNull(timer.elapsedMinutes)
      assertNull(timer.description)
    }

  // --- projects ---------------------------------------------------------------------------------

  @Test
  fun `projects keep the client name an agent needs to disambiguate them`() = runTest {
    signIn()
    server.enqueue(MockResponse().setBody(projectsPageJson()))

    val projects = appFunctions.projects()

    assertEquals(
      listOf(ProjectSummary(id = PROJECT_ID, name = "Website", clientName = "Acme")),
      projects,
    )
  }

  // --- recent entries ---------------------------------------------------------------------------

  @Test
  fun `recent entries carry their project name and a whole-minute duration`() = runTest {
    signIn()
    server.enqueue(MockResponse().setBody(projectsPageJson()))
    server.enqueue(
      MockResponse()
        .setBody("[${timeEntryJson(start = "2026-07-31T08:00:00Z", end = "2026-07-31T08:30:00Z")}]")
    )

    val entries = appFunctions.recentEntries()

    assertEquals(1, entries.size)
    assertEquals("Website", entries.single().projectName)
    assertEquals(30L, entries.single().durationMinutes)
  }

  // Clockify lists the entry that is still running among the recent ones, with no end.
  @Test
  fun `a recent entry that is still running has no duration rather than zero minutes`() = runTest {
    signIn()
    server.enqueue(MockResponse().setBody(projectsPageJson()))
    server.enqueue(MockResponse().setBody("[${timeEntryJson(start = "2026-07-31T09:00:00Z")}]"))

    val entries = appFunctions.recentEntries()

    assertNull(entries.single().durationMinutes)
  }

  @Test
  fun `a failed project lookup leaves the name absent rather than failing the call`() = runTest {
    signIn()
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(
      MockResponse()
        .setBody("[${timeEntryJson(start = "2026-07-31T08:00:00Z", end = "2026-07-31T08:30:00Z")}]")
    )

    val entries = appFunctions.recentEntries()

    assertEquals(1, entries.size)
    assertNull(entries.single().projectName)
    assertEquals(30L, entries.single().durationMinutes)
  }
}
