package fi.nikosavola.clockifywear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.nikosavola.clockifywear.data.api.ClockifyApi
import fi.nikosavola.clockifywear.data.api.clockifyJson
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.StartTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.StopTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.TaskDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.data.api.dto.UserDto
import fi.nikosavola.clockifywear.data.api.dto.WorkspaceDto
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val OTHER_WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d99"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val API_KEY = "test-api-key"
private const val EMAIL = "user@example.com"

private fun userJson(
  activeWorkspace: String? = null,
  defaultWorkspace: String? = null,
  email: String? = null,
): String =
  clockifyJson.encodeToString(
    UserDto.serializer(),
    UserDto(
      id = USER_ID,
      email = email,
      activeWorkspace = activeWorkspace,
      defaultWorkspace = defaultWorkspace,
    ),
  )

private fun projectJson(id: String, archived: Boolean = false): String =
  """{"id": "$id", "name": "Project $id", "archived": $archived}"""

private fun projectsPageJson(
  count: Int,
  prefix: String,
  archivedIds: Set<String> = emptySet(),
): String =
  (0 until count).joinToString(prefix = "[", postfix = "]") { i ->
    val id = "$prefix$i"
    projectJson(id, archived = id in archivedIds)
  }

private fun timeEntryJson(id: String, start: String = "2026-07-31T09:00:00Z"): String =
  """{"id": "$id", "timeInterval": {"start": "$start"}}"""

@RunWith(RobolectricTestRunner::class)
class ClockifyRepositoryTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var api: ClockifyApi
  private lateinit var settingsStore: SettingsStore
  private lateinit var projectCache: ProjectCache
  private lateinit var repository: ClockifyRepository
  private var now: Instant = Instant.parse("2026-07-31T09:00:00Z")

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
    projectCache = ProjectCache(File(tempFolder.root, "projects.json")) { now }
    repository = ClockifyRepository(api, settingsStore, projectCache) { now }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private suspend fun primeIdentity(workspaceId: String = WORKSPACE_ID, userId: String = USER_ID) {
    settingsStore.setWorkspaceId(workspaceId)
    settingsStore.setUserId(userId)
  }

  // --- signIn ---------------------------------------------------------------------------------

  @Test
  fun `signIn persists userId and the active workspace on success`() = runTest {
    server.enqueue(MockResponse().setBody(userJson(activeWorkspace = WORKSPACE_ID)))

    val result = repository.signIn(API_KEY)

    assertTrue(result is ClockifyResult.Success)
    val settings = settingsStore.currentSettings()
    assertEquals(API_KEY, settings.apiKey)
    assertEquals(USER_ID, settings.userId)
    assertEquals(WORKSPACE_ID, settings.workspaceId)
  }

  @Test
  fun `signIn persists the email from the user response`() = runTest {
    server.enqueue(MockResponse().setBody(userJson(activeWorkspace = WORKSPACE_ID, email = EMAIL)))

    repository.signIn(API_KEY)

    assertEquals(EMAIL, settingsStore.currentSettings().email)
  }

  @Test
  fun `signIn persists a null email when the user response omits it`() = runTest {
    server.enqueue(MockResponse().setBody(userJson(activeWorkspace = WORKSPACE_ID)))

    repository.signIn(API_KEY)

    assertNull(settingsStore.currentSettings().email)
  }

  @Test
  fun `signIn falls back to defaultWorkspace when activeWorkspace is absent`() = runTest {
    server.enqueue(MockResponse().setBody(userJson(defaultWorkspace = WORKSPACE_ID)))

    repository.signIn(API_KEY)

    assertEquals(WORKSPACE_ID, settingsStore.currentSettings().workspaceId)
  }

  @Test
  fun `signIn falls back to the first workspace when neither workspace field is set`() = runTest {
    server.enqueue(MockResponse().setBody(userJson()))
    server.enqueue(
      MockResponse()
        .setBody(
          """[{"id": "$WORKSPACE_ID", "name": "First"}, {"id": "$OTHER_WORKSPACE_ID", "name": "Second"}]"""
        )
    )

    val result = repository.signIn(API_KEY)

    assertTrue(result is ClockifyResult.Success)
    assertEquals(WORKSPACE_ID, settingsStore.currentSettings().workspaceId)
  }

  @Test
  fun `signIn clears the api key and fails when the key is rejected`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))

    val result = repository.signIn(API_KEY)

    assertEquals(ClockifyResult.Failure(ClockifyError.Unauthorized), result)
    assertNull(settingsStore.currentSettings().apiKey)
  }

  @Test
  fun `signIn clears the api key and fails when the account has no workspace`() = runTest {
    server.enqueue(MockResponse().setBody(userJson()))
    server.enqueue(MockResponse().setBody("[]"))

    val result = repository.signIn(API_KEY)

    assertEquals(ClockifyResult.Failure(ClockifyError.NoWorkspaceFound), result)
    assertNull(settingsStore.currentSettings().apiKey)
  }

  // --- workspaces -------------------------------------------------------------------------------

  @Test
  fun `workspaces returns the parsed list`() = runTest {
    server.enqueue(MockResponse().setBody("""[{"id": "$WORKSPACE_ID", "name": "Personal"}]"""))

    val result = repository.workspaces()

    assertEquals(ClockifyResult.Success(listOf(WorkspaceDto(WORKSPACE_ID, "Personal"))), result)
  }

  // --- projects: pagination ---------------------------------------------------------------------

  @Test
  fun `projects returns a single short page in one request`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setBody(projectsPageJson(3, "p")))

    val result = repository.projects()

    assertTrue(result is ClockifyResult.Success)
    assertEquals(3, (result as ClockifyResult.Success).value.size)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `projects follows a full page with a short page`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setBody(projectsPageJson(PROJECTS_PAGE_SIZE, "a")))
    server.enqueue(MockResponse().setBody(projectsPageJson(5, "b")))

    val result = repository.projects()

    assertTrue(result is ClockifyResult.Success)
    assertEquals(PROJECTS_PAGE_SIZE + 5, (result as ClockifyResult.Success).value.size)
    assertEquals(2, server.requestCount)
    assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("page"))
    assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("page"))
  }

  @Test
  fun `projects stops after MAX_PROJECT_PAGES full pages instead of looping forever`() = runTest {
    primeIdentity()
    repeat(MAX_PROJECT_PAGES) {
      server.enqueue(MockResponse().setBody(projectsPageJson(PROJECTS_PAGE_SIZE, "c")))
    }

    val result = repository.projects()

    assertTrue(result is ClockifyResult.Success)
    assertEquals(
      MAX_PROJECT_PAGES * PROJECTS_PAGE_SIZE,
      (result as ClockifyResult.Success).value.size,
    )
    assertEquals(MAX_PROJECT_PAGES, server.requestCount)
  }

  @Test
  fun `projects filters out archived projects`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setBody(projectsPageJson(3, "p", archivedIds = setOf("p1"))))

    val result = repository.projects()

    assertTrue(result is ClockifyResult.Success)
    val ids = (result as ClockifyResult.Success).value.map { it.id }
    assertEquals(listOf("p0", "p2"), ids)
  }

  @Test
  fun `projects serves a fresh cache without making any HTTP requests`() = runTest {
    primeIdentity()
    projectCache.write(listOf(ProjectDto(id = "cached", name = "Cached")))

    val result = repository.projects()

    assertTrue(result is ClockifyResult.Success)
    assertEquals("cached", (result as ClockifyResult.Success).value.single().id)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `projects with forceRefresh bypasses a fresh cache and hits the network`() = runTest {
    primeIdentity()
    projectCache.write(listOf(ProjectDto(id = "cached", name = "Cached")))
    server.enqueue(MockResponse().setBody(projectsPageJson(2, "n")))

    val result = repository.projects(forceRefresh = true)

    assertTrue(result is ClockifyResult.Success)
    assertEquals(2, (result as ClockifyResult.Success).value.size)
    assertEquals(1, server.requestCount)
  }

  // --- tasks
  // --------------------------------------------------------------------------------------

  @Test
  fun `tasks hits the project tasks endpoint for the given project`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))

    repository.tasks(PROJECT_ID)

    val url = server.takeRequest().requestUrl!!
    assertEquals("/workspaces/$WORKSPACE_ID/projects/$PROJECT_ID/tasks", url.encodedPath)
  }

  // --- startTimer / stopTimer --------------------------------------------------------------------

  @Test
  fun `startTimer stops any running entry before starting the new one, in order`() = runTest {
    primeIdentity()
    server.enqueue(timeEntryResponse("running-entry"))
    server.enqueue(timeEntryResponse("new-entry"))

    val result = repository.startTimer(projectId = PROJECT_ID)

    assertTrue(result is ClockifyResult.Success)
    val stopRequest = server.takeRequest()
    assertEquals("PATCH", stopRequest.method)
    assertEquals("/workspaces/$WORKSPACE_ID/user/$USER_ID/time-entries", stopRequest.path)
    val startRequest = server.takeRequest()
    assertEquals("POST", startRequest.method)
    assertEquals("/workspaces/$WORKSPACE_ID/time-entries", startRequest.path)
  }

  @Test
  fun `startTimer treats a 404 on the stop step as nothing-was-running and still starts`() =
    runTest {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(timeEntryResponse("new-entry"))

      val result = repository.startTimer(projectId = PROJECT_ID)

      assertTrue(result is ClockifyResult.Success)
      assertEquals(2, server.requestCount)
      assertEquals("new-entry", repository.runningEntry.value?.id)
    }

  @Test
  fun `startTimer does not attempt to start when the stop step fails for a real reason`() =
    runTest {
      primeIdentity()
      server.enqueue(MockResponse().setResponseCode(500))

      val result = repository.startTimer(projectId = PROJECT_ID)

      assertEquals(ClockifyResult.Failure(ClockifyError.Http(500)), result)
      assertEquals(1, server.requestCount)
    }

  @Test
  fun `stopTimer clears runningEntry on success`() = runTest {
    primeIdentity()
    server.enqueue(timeEntryResponse("stopped"))

    val result = repository.stopTimer()

    assertTrue(result is ClockifyResult.Success)
    assertNull(repository.runningEntry.value)
  }

  @Test
  fun `stopTimer maps a 404 to success meaning nothing was running`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setResponseCode(404))

    val result = repository.stopTimer()

    assertEquals(ClockifyResult.Success(Unit), result)
  }

  // --- fetchRunningEntry / recentEntries
  // -----------------------------------------------------------

  @Test
  fun `fetchRunningEntry returns null and updates runningEntry when idle`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))

    val result = repository.fetchRunningEntry()

    assertEquals(ClockifyResult.Success(null), result)
    assertNull(repository.runningEntry.value)
  }

  @Test
  fun `fetchRunningEntry returns the entry and updates runningEntry when one is running`() =
    runTest {
      primeIdentity()
      server.enqueue(MockResponse().setBody("[${timeEntryJson("running")}]"))

      val result = repository.fetchRunningEntry()

      assertTrue(result is ClockifyResult.Success)
      assertEquals("running", (result as ClockifyResult.Success).value?.id)
      assertEquals("running", repository.runningEntry.value?.id)
    }

  @Test
  fun `recentEntries dedups by project, task and description and caps at 10`() = runTest {
    primeIdentity()
    val entries =
      (0 until 15).joinToString(prefix = "[", postfix = "]") { i ->
        // Every entry shares the same project/task/description so they all collapse to one.
        """{"id": "e$i", "projectId": "$PROJECT_ID", "timeInterval": {"start": "2026-07-31T0${i % 9}:00:00Z"}}"""
      }
    server.enqueue(MockResponse().setBody(entries))

    val result = repository.recentEntries()

    assertTrue(result is ClockifyResult.Success)
    assertEquals(1, (result as ClockifyResult.Success).value.size)
  }

  // MockWebServer returns whatever is enqueued regardless of page-size, so the dedup tests above
  // cannot catch a fetch window that is too small to survive dedup. Assert the query instead.
  @Test
  fun `recentEntries requests more entries than it displays so dedup has headroom`() = runTest {
    primeIdentity()
    server.enqueue(MockResponse().setBody("[]"))

    repository.recentEntries()

    val requested =
      server.takeRequest().requestUrl?.queryParameter("page-size")?.toInt() ?: error("no page-size")
    assertTrue(requested > RECENT_ENTRIES_LIMIT)
    assertEquals(RECENT_ENTRIES_FETCH_SIZE, requested)
  }

  @Test
  fun `recentEntries keeps distinct combinations up to the limit`() = runTest {
    primeIdentity()
    val entries =
      (0 until 15).joinToString(prefix = "[", postfix = "]") { i ->
        """{"id": "e$i", "projectId": "p$i", "timeInterval": {"start": "2026-07-31T09:00:00Z"}}"""
      }
    server.enqueue(MockResponse().setBody(entries))

    val result = repository.recentEntries()

    assertTrue(result is ClockifyResult.Success)
    assertEquals(RECENT_ENTRIES_LIMIT, (result as ClockifyResult.Success).value.size)
  }

  // --- missing identity
  // -----------------------------------------------------------------------------

  @Test
  fun `operations needing identity fail with NotSignedIn instead of crashing when unset`() =
    runTest {
      val tasksResult = repository.tasks(PROJECT_ID)
      val startResult = repository.startTimer(PROJECT_ID)
      val stopResult = repository.stopTimer()

      assertEquals(ClockifyResult.Failure(ClockifyError.NotSignedIn), tasksResult)
      assertEquals(ClockifyResult.Failure(ClockifyError.NotSignedIn), startResult)
      assertEquals(ClockifyResult.Failure(ClockifyError.NotSignedIn), stopResult)
      assertEquals(0, server.requestCount)
    }

  // --- error mapping
  // ----------------------------------------------------------------------------------

  @Test
  fun `401 maps to Unauthorized`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))

    assertEquals(ClockifyResult.Failure(ClockifyError.Unauthorized), repository.workspaces())
  }

  @Test
  fun `a repeated 429 maps to RateLimited after the client's single retry also fails`() = runTest {
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))

    val result = repository.workspaces()

    assertEquals(ClockifyResult.Failure(ClockifyError.RateLimited), result)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun `500 maps to a generic Http error carrying the status code`() = runTest {
    server.enqueue(MockResponse().setResponseCode(500))

    assertEquals(ClockifyResult.Failure(ClockifyError.Http(500)), repository.workspaces())
  }

  @Test
  fun `malformed JSON maps to ParseError`() = runTest {
    server.enqueue(MockResponse().setBody("not valid json"))

    assertEquals(ClockifyResult.Failure(ClockifyError.ParseError), repository.workspaces())
  }

  @Test
  fun `a connection failure maps to Offline`() = runTest {
    // Nothing listens on this port: the client fails at connect time with an IOException,
    // independent of the shared MockWebServer instance used by the other tests.
    val unreachableApi = createClockifyApi(apiKey = { API_KEY }, baseUrl = "http://127.0.0.1:1/")
    val offlineRepository = ClockifyRepository(unreachableApi, settingsStore, projectCache) { now }

    val result = offlineRepository.workspaces()

    assertEquals(ClockifyResult.Failure(ClockifyError.Offline), result)
  }

  // Both catch chains in the repository (runCatchingClockify and stopRunningEntry) must rethrow
  // CancellationException rather than mapping it like an IOException. A fake that throws it
  // directly keeps this deterministic; cancelling a real in-flight request would depend on thread
  // timing.
  @Test
  fun `cancellation propagates out of runCatchingClockify instead of mapping to an error`() =
    runTest {
      val cancelling = ClockifyRepository(CancellingApi, settingsStore, projectCache) { now }

      try {
        cancelling.workspaces()
        fail("expected CancellationException to propagate")
      } catch (expected: CancellationException) {
        assertEquals(CANCEL_MESSAGE, expected.message)
      }
    }

  @Test
  fun `cancellation propagates out of the stop-timer catch chain`() = runTest {
    primeIdentity()
    val cancelling = ClockifyRepository(CancellingApi, settingsStore, projectCache) { now }

    try {
      cancelling.stopTimer()
      fail("expected CancellationException to propagate")
    } catch (expected: CancellationException) {
      assertEquals(CANCEL_MESSAGE, expected.message)
    }
  }

  private fun timeEntryResponse(id: String): MockResponse =
    MockResponse().setBody(timeEntryJson(id))
}

private const val CANCEL_MESSAGE = "cancelled mid-request"

private object CancellingApi : ClockifyApi {
  override suspend fun getCurrentUser(): UserDto = throw CancellationException(CANCEL_MESSAGE)

  override suspend fun getWorkspaces(): List<WorkspaceDto> =
    throw CancellationException(CANCEL_MESSAGE)

  override suspend fun getProjects(
    workspaceId: String,
    archived: Boolean?,
    page: Int?,
    pageSize: Int?,
  ): List<ProjectDto> = throw CancellationException(CANCEL_MESSAGE)

  override suspend fun getProjectTasks(
    workspaceId: String,
    projectId: String,
    isActive: Boolean?,
    page: Int?,
    pageSize: Int?,
  ): List<TaskDto> = throw CancellationException(CANCEL_MESSAGE)

  override suspend fun startTimeEntry(
    workspaceId: String,
    request: StartTimeEntryRequest,
  ): TimeEntryDto = throw CancellationException(CANCEL_MESSAGE)

  override suspend fun stopTimeEntry(
    workspaceId: String,
    userId: String,
    request: StopTimeEntryRequest,
  ): TimeEntryDto = throw CancellationException(CANCEL_MESSAGE)

  override suspend fun getRunningTimeEntry(
    workspaceId: String,
    userId: String,
    inProgress: Boolean,
  ): List<TimeEntryDto> = throw CancellationException(CANCEL_MESSAGE)

  override suspend fun getRecentTimeEntries(
    workspaceId: String,
    userId: String,
    pageSize: Int,
  ): List<TimeEntryDto> = throw CancellationException(CANCEL_MESSAGE)
}
