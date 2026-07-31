package fi.nikosavola.clockifywear.data

import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCacheTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var file: File
  private var now = Instant.parse("2026-07-31T09:00:00Z")

  @Before
  fun setUp() {
    file = File(tempFolder.root, "projects.json")
  }

  private fun cache() = ProjectCache(file) { now }

  @Test
  fun `read returns null when the cache file is absent`() {
    assertNull(cache().read())
  }

  @Test
  fun `write then read round-trips the projects and fetch time`() {
    val projects =
      listOf(
        ProjectDto(id = "p1", name = "Project One", color = "#FF0000"),
        ProjectDto(id = "p2", name = "Project Two"),
      )

    cache().write(projects)
    val result = cache().read()

    assertNotNull(result)
    assertEquals(projects, result!!.projects)
    assertEquals(now, result.fetchedAt)
  }

  @Test
  fun `read returns null and does not throw for a corrupt cache file`() {
    file.writeText("{not valid json at all")

    assertNull(cache().read())
  }

  @Test
  fun `read returns null and does not throw for an empty cache file`() {
    file.writeText("")

    assertNull(cache().read())
  }

  // Structurally valid JSON with an unparseable timestamp fails inside Instant.parse, not in the
  // JSON decoder, so it does not surface as SerializationException like the cases above.
  @Test
  fun `read returns null and does not throw when fetchedAt is not a timestamp`() {
    file.writeText("""{"fetchedAt": "not-a-timestamp", "projects": []}""")

    assertNull(cache().read())
  }

  @Test
  fun `isStale is false just under the threshold`() {
    val cached = CachedProjects(fetchedAt = now, projects = emptyList())
    now = cached.fetchedAt.plus(Duration.ofHours(6).minusSeconds(1))

    assertFalse(cache().isStale(cached))
  }

  @Test
  fun `isStale is true just over the threshold`() {
    val cached = CachedProjects(fetchedAt = now, projects = emptyList())
    now = cached.fetchedAt.plus(Duration.ofHours(6).plusSeconds(1))

    assertTrue(cache().isStale(cached))
  }
}
