package fi.nikosavola.clockifywear.data.api

import fi.nikosavola.clockifywear.data.api.dto.StartTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.StopTimeEntryRequest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

private const val WORKSPACE_ID = "5f8a1b2c3d4e5f6a7b8c9d10"
private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"
private const val USER_ID = "5f8a1b2c3d4e5f6a7b8c9d0e"
private const val API_KEY = "test-api-key"

class ClockifyApiTest {
  private lateinit var server: MockWebServer
  private lateinit var api: ClockifyApi

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    api = createClockifyApi(apiKey = { API_KEY }, baseUrl = server.url("/").toString())
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `getCurrentUser hits GET user with the api key header`() {
    runTest {
      server.enqueue(MockResponse().setBody("""{"id": "$USER_ID"}"""))

      val user = api.getCurrentUser()

      assertEquals(USER_ID, user.id)
      val recorded = server.takeRequest()
      assertEquals("GET", recorded.method)
      assertEquals("/user", recorded.path)
      assertEquals(API_KEY, recorded.getHeader("X-Api-Key"))
    }
  }

  @Test
  fun `null api key omits the header`() {
    runTest {
      api = createClockifyApi(apiKey = { null }, baseUrl = server.url("/").toString())
      server.enqueue(MockResponse().setBody("""{"id": "$USER_ID"}"""))

      api.getCurrentUser()

      assertNull(server.takeRequest().getHeader("X-Api-Key"))
    }
  }

  @Test
  fun `default base url is accepted by Retrofit`() {
    // Retrofit.Builder validates baseUrl at build time (must end in "/"); this guards the
    // one constant that ships against a typo with no other coverage.
    createClockifyApi(apiKey = { null })
  }

  @Test
  fun `getWorkspaces hits GET workspaces`() {
    runTest {
      server.enqueue(MockResponse().setBody("""[{"id": "$WORKSPACE_ID", "name": "Personal"}]"""))

      val workspaces = api.getWorkspaces()

      assertEquals(1, workspaces.size)
      assertEquals(WORKSPACE_ID, workspaces[0].id)
      assertEquals("/workspaces", server.takeRequest().path)
    }
  }

  @Test
  fun `getProjects hits the workspace path with the given query params`() {
    runTest {
      server.enqueue(MockResponse().setBody("[]"))

      api.getProjects(WORKSPACE_ID, archived = false, page = 1, pageSize = 200)

      val recorded = server.takeRequest()
      assertEquals("GET", recorded.method)
      val url = recorded.requestUrl!!
      assertEquals("/workspaces/$WORKSPACE_ID/projects", url.encodedPath)
      assertEquals("false", url.queryParameter("archived"))
      assertEquals("1", url.queryParameter("page"))
      assertEquals("200", url.queryParameter("page-size"))
    }
  }

  @Test
  fun `getProjectTasks hits the project tasks path with is-active`() {
    runTest {
      server.enqueue(MockResponse().setBody("[]"))

      api.getProjectTasks(WORKSPACE_ID, PROJECT_ID, isActive = true)

      val recorded = server.takeRequest()
      val url = recorded.requestUrl!!
      assertEquals("/workspaces/$WORKSPACE_ID/projects/$PROJECT_ID/tasks", url.encodedPath)
      assertEquals("true", url.queryParameter("is-active"))
    }
  }

  @Test
  fun `startTimeEntry posts to time-entries with the request body`() {
    runTest {
      val entryId = "5f8a1b2c3d4e5f6a7b8c9d40"
      server.enqueue(
        MockResponse()
          .setBody("""{"id": "$entryId", "timeInterval": {"start": "2026-07-31T09:00:00Z"}}""")
      )

      val entry =
        api.startTimeEntry(
          WORKSPACE_ID,
          StartTimeEntryRequest(
            start = Instant.parse("2026-07-31T09:00:00Z"),
            projectId = PROJECT_ID,
          ),
        )

      assertEquals(entryId, entry.id)
      val recorded = server.takeRequest()
      assertEquals("POST", recorded.method)
      assertEquals("/workspaces/$WORKSPACE_ID/time-entries", recorded.path)
      assertEquals(
        """{"start":"2026-07-31T09:00:00Z","projectId":"$PROJECT_ID"}""",
        recorded.body.readUtf8(),
      )
    }
  }

  @Test
  fun `stopTimeEntry patches the user time-entries path with the end body`() {
    runTest {
      val entryId = "5f8a1b2c3d4e5f6a7b8c9d40"
      server.enqueue(
        MockResponse()
          .setBody(
            """{"id": "$entryId", """ +
              """"timeInterval": {"start": "2026-07-31T09:00:00Z", "end": "2026-07-31T10:00:00Z"}}"""
          )
      )

      api.stopTimeEntry(
        WORKSPACE_ID,
        USER_ID,
        StopTimeEntryRequest(end = Instant.parse("2026-07-31T10:00:00Z")),
      )

      val recorded = server.takeRequest()
      assertEquals("PATCH", recorded.method)
      assertEquals("/workspaces/$WORKSPACE_ID/user/$USER_ID/time-entries", recorded.path)
      assertEquals("""{"end":"2026-07-31T10:00:00Z"}""", recorded.body.readUtf8())
    }
  }

  @Test
  fun `getRunningTimeEntry returns an empty array when idle`() {
    runTest {
      server.enqueue(MockResponse().setBody("[]"))

      val running = api.getRunningTimeEntry(WORKSPACE_ID, USER_ID)

      assertTrue(running.isEmpty())
      val url = server.takeRequest().requestUrl!!
      assertEquals("/workspaces/$WORKSPACE_ID/user/$USER_ID/time-entries", url.encodedPath)
      assertEquals("true", url.queryParameter("in-progress"))
    }
  }

  @Test
  fun `getRunningTimeEntry returns one entry when a timer is running`() {
    runTest {
      val entryId = "5f8a1b2c3d4e5f6a7b8c9d40"
      server.enqueue(
        MockResponse()
          .setBody("""[{"id": "$entryId", "timeInterval": {"start": "2026-07-31T09:00:00Z"}}]""")
      )

      val running = api.getRunningTimeEntry(WORKSPACE_ID, USER_ID)

      assertEquals(1, running.size)
      assertEquals(entryId, running[0].id)
    }
  }

  @Test
  fun `getRecentTimeEntries sends page-size and no in-progress filter`() {
    runTest {
      server.enqueue(MockResponse().setBody("[]"))

      api.getRecentTimeEntries(WORKSPACE_ID, USER_ID, pageSize = 10)

      val url = server.takeRequest().requestUrl!!
      assertEquals("10", url.queryParameter("page-size"))
      assertNull(url.queryParameter("in-progress"))
    }
  }

  @Test
  fun `retries once on 429 honoring Retry-After and returns the eventual success`() {
    runTest {
      server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
      server.enqueue(MockResponse().setBody("""{"id": "$USER_ID"}"""))

      val user = api.getCurrentUser()

      assertEquals(USER_ID, user.id)
      assertEquals(2, server.requestCount)
    }
  }

  @Test
  fun `a second consecutive 429 surfaces as an error instead of retrying again`() {
    runTest {
      server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
      server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))

      try {
        api.getCurrentUser()
        fail("expected HttpException")
      } catch (e: HttpException) {
        assertEquals(429, e.code())
      }
      assertEquals(2, server.requestCount)
    }
  }
}
