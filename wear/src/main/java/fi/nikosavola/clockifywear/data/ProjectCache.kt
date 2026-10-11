package fi.nikosavola.clockifywear.data

import fi.nikosavola.clockifywear.data.api.InstantSerializer
import fi.nikosavola.clockifywear.data.api.clockifyJson
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

private const val TEMP_SUFFIX = ".tmp"

@Serializable
data class CachedProjects(
  @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
  val projects: List<ProjectDto>,
)

// Past this age the picker still renders the cache instantly but a refresh should be triggered
// in the background.
private val CACHE_STALENESS_THRESHOLD: Duration = Duration.ofHours(6)

/**
 * Caches the project list as a single JSON file rather than in DataStore: the list can run to tens
 * of KB, a poor fit for Preferences DataStore.
 *
 * The decoded list is kept in memory between writes. Every tile and complication refresh reads it
 * (through `ClockifyRepository.projects`), so re-decoding a whole workspace's projects each time is
 * most of what those calls cost on a watch.
 *
 * @param file where the cache is stored; a temp file in tests.
 * @param clock injectable so staleness can be tested without sleeping.
 */
class ProjectCache(private val file: File, private val clock: () -> Instant = Instant::now) {
  // Wraps the nullable result so "not read yet" is distinguishable from "read: no cache", which a
  // bare nullable field cannot express. One immutable object behind one atomic reference, so a
  // concurrent read can never pair a loaded value with the wrong state.
  private class Memo(val cached: CachedProjects?)

  private val memo = AtomicReference<Memo?>()

  /** Returns null when the cache is absent, unreadable, or unparseable; never throws. */
  fun read(): CachedProjects? {
    memo.get()?.let {
      return it.cached
    }
    val loaded = Memo(readFromDisk())
    // Installed only if nothing landed meanwhile: whatever got there while this was decoding is
    // newer than what was just read off the disk, so it wins rather than being overwritten.
    memo.compareAndSet(null, loaded)
    return (memo.get() ?: loaded).cached
  }

  fun write(projects: List<ProjectDto>) {
    val cached = CachedProjects(fetchedAt = clock(), projects = projects)
    val json = clockifyJson.encodeToString(CachedProjects.serializer(), cached)
    // Written beside the cache and moved over it in one step: a reader in another coroutine, or a
    // crash, must never find a half-written file. Corrupt JSON used to cost one failed read; now
    // that a failed read is memoized it would leave this process serving nothing from the cache
    // until the next successful fetch.
    //
    // The temp name has to be unique per write, which is what createTempFile gives: a fixed
    // "<name>.tmp" would be shared by every refill in flight, and the app, the tile and the
    // complication can all refill this one cache at once, so one writer would truncate the file
    // another was about to move into place and publish the half-written result of the first. The
    // cache's own name is the prefix so an orphan left by a crash is recognisable.
    var temp: Path? = null
    try {
      val directory = Files.createDirectories(file.absoluteFile.parentFile.toPath())
      temp = Files.createTempFile(directory, file.name, TEMP_SUFFIX)
      Files.write(temp, json.toByteArray(Charsets.UTF_8))
      Files.move(
        temp,
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: IOException) {
      discard(temp)
      // Best-effort: a fresh network fetch already succeeded, so a disk write failure here
      // should not fail the caller's operation, only leave the cache stale for next time. The
      // memo is deliberately not primed either, for the same reason: this process's next read
      // must see what is actually on disk.
      return
    }
    memo.set(Memo(cached))
  }

  // Removes a temp file whose write did not land. The delete is itself allowed to fail: a leftover
  // is harmless, since it is named after the cache and the next write gets a name of its own.
  private fun discard(temp: Path?) {
    temp ?: return
    try {
      Files.delete(temp)
    } catch (_: IOException) {
      // Nothing left to try.
    }
  }

  fun isStale(cached: CachedProjects): Boolean =
    Duration.between(cached.fetchedAt, clock()) > CACHE_STALENESS_THRESHOLD

  private fun readFromDisk(): CachedProjects? {
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
}
