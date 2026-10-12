package fi.nikosavola.clockifywear.data

import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
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
  private val projects = listOf(ProjectDto(id = "p1", name = "Project One", color = "#FF0000"))

  @Before
  fun setUp() {
    file = File(tempFolder.root, "projects.json")
  }

  private fun cache() = ProjectCache(file) { now }

  @Test
  fun `read returns null when the cache file is absent`() {
    assertNull(cache().read())
  }

  // The point of the in-memory copy: a tile or complication refresh must not pay to decode the
  // project list again. Rewriting the file behind the cache's back stands in for any second read.
  @Test
  fun `read does not go back to the file once it has been read`() {
    val cache = cache()
    cache.write(projects)
    assertEquals(projects, cache.read()!!.projects)

    file.writeText("{not valid json at all")

    assertEquals(projects, cache.read()!!.projects)
  }

  @Test
  fun `write replaces what read serves`() {
    val cache = cache()
    cache.write(projects)
    assertEquals(projects, cache.read()!!.projects)

    val replacement = listOf(ProjectDto(id = "p2", name = "Project Two"))
    cache.write(replacement)

    assertEquals(replacement, cache.read()!!.projects)
  }

  // A file where the parent directory needs to be, so both mkdirs() and the write itself fail.
  @Test
  fun `a write that never reached the disk is not served by read`() {
    val blocked = File(tempFolder.root, "blocked").apply { writeText("not a directory") }
    val cache = ProjectCache(File(blocked, "projects.json")) { now }

    cache.write(projects)

    assertNull(cache.read())
  }

  // The write goes through a temp file, so no reader can find a half-written one, and the temp must
  // not survive it: a fresh instance (no memo of its own) sees the renamed result.
  @Test
  fun `write goes through a temp file that does not survive it`() {
    cache().write(projects)

    assertEquals(listOf("projects.json"), tempFolder.root.listFiles()!!.map { it.name }.sorted())
    assertEquals(projects, cache().read()!!.projects)
  }

  // A directory where the cache file goes, so the rename onto it fails. The temp file must not be
  // left behind, and the failure must not be memoized as if the write had landed.
  @Test
  fun `a write that cannot replace the file leaves no temp behind and is not served by read`() {
    val blocked = File(tempFolder.root, "projects.json").apply { mkdirs() }
    val cache = ProjectCache(blocked) { now }

    cache.write(projects)

    assertTrue(blocked.isDirectory)
    assertEquals(listOf("projects.json"), tempFolder.root.listFiles()!!.map { it.name }.sorted())
    assertNull(cache.read())
  }

  // The app, the tile and the complication share this one cache and can each be refilling it at the
  // same moment, which is why every write gets a temp file of its own. Sharing one temp path
  // instead lets the second writer truncate the file the first is about to rename into place,
  // leaving a half-written cache - and, because a failed read is now memoized, keeping this process
  // reading nothing from it until it restarts.
  @Test
  fun `writes racing each other each leave a whole list behind and no temp file`() {
    val cache = cache()

    // Repeated rounds, because which of the two writers wins the race is up to the scheduler: one
    // round would only sometimes land in the window this is meant to catch.
    repeat(10) { round ->
      val first = List(200) { ProjectDto(id = "a-$round-$it", name = "First project $it") }
      val second = List(200) { ProjectDto(id = "b-$round-$it", name = "Second project $it") }
      val startTogether = CountDownLatch(1)

      val writers =
        listOf(first, second).map { projects ->
          thread {
            startTogether.await()
            cache.write(projects)
          }
        }
      startTogether.countDown()
      writers.forEach { it.join() }

      // A fresh instance, so this reads what is on disk rather than anything the writes memoized.
      val onDisk = cache().read()
      assertNotNull("round $round left nothing readable", onDisk)
      assertTrue("round $round left a mixed list", onDisk!!.projects in listOf(first, second))
    }
    assertEquals(listOf("projects.json"), tempFolder.root.listFiles()!!.map { it.name }.sorted())
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
