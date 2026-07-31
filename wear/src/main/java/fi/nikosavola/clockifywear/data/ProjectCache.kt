package fi.nikosavola.clockifywear.data

import fi.nikosavola.clockifywear.data.api.InstantSerializer
import fi.nikosavola.clockifywear.data.api.clockifyJson
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class CachedProjects(
  @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
  val projects: List<ProjectDto>,
)

// PLANNING.md "Cache staleness": past this age the picker still renders the cache instantly but
// a refresh should be triggered in the background.
private val CACHE_STALENESS_THRESHOLD: Duration = Duration.ofHours(6)

/**
 * Caches the project list as a single JSON file rather than in DataStore: the list can run to tens
 * of KB, a poor fit for Preferences DataStore.
 *
 * @param file where the cache is stored; a temp file in tests.
 * @param clock injectable so staleness can be tested without sleeping.
 */
class ProjectCache(private val file: File, private val clock: () -> Instant = Instant::now) {
  /** Returns null when the cache is absent, unreadable, or unparseable; never throws. */
  fun read(): CachedProjects? {
    if (!file.exists()) return null
    return try {
      clockifyJson.decodeFromString(CachedProjects.serializer(), file.readText())
    } catch (e: SerializationException) {
      // A corrupt or partially-written cache file must not crash the app; treat it as absent.
      null
    } catch (e: DateTimeParseException) {
      // Thrown from inside InstantSerializer when fetchedAt is well-formed JSON but not a valid
      // timestamp, so it never reaches the SerializationException branch above.
      null
    } catch (e: IOException) {
      null
    }
  }

  fun write(projects: List<ProjectDto>) {
    val cached = CachedProjects(fetchedAt = clock(), projects = projects)
    val json = clockifyJson.encodeToString(CachedProjects.serializer(), cached)
    try {
      file.parentFile?.mkdirs()
      file.writeText(json)
    } catch (e: IOException) {
      // Best-effort: a fresh network fetch already succeeded, so a disk write failure here
      // should not fail the caller's operation, only leave the cache stale for next time.
    }
  }

  fun isStale(cached: CachedProjects): Boolean =
    Duration.between(cached.fetchedAt, clock()) > CACHE_STALENESS_THRESHOLD
}
