package fi.nikosavola.clockifywear.tile

import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.data.api.dto.TimeIntervalDto
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JVM unit tests: TileClickResolver takes plain suspend lambdas, no Android/Robolectric
// dependency needed to exercise its replay-safety and concurrency guarantees.
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent()
class TileClickResolverTest {
  private fun entry(id: String) =
    TimeEntryDto(id = id, timeInterval = TimeIntervalDto(start = Instant.now()))

  // A TileClickTokenStore backed by a plain, non-atomic list - see the concurrency test's note.
  private fun fakeTokenStore(consumed: MutableList<String> = mutableListOf()) =
    TileClickTokenStore(
      isConsumed = { token -> consumed.contains(token) },
      markConsumed = { token -> consumed.add(token) },
    )

  @Test
  fun `replayed start click id calls startTimer exactly once`() = runTest {
    var startCalls = 0
    val repository =
      TileActionRepository(
        fetchRunningEntry = { ClockifyResult.Success(null) },
        startTimer = {
          startCalls++
          ClockifyResult.Success(entry("e1"))
        },
        stopTimer = { ClockifyResult.Success(Unit) },
      )
    val resolver = TileClickResolver()
    val tokenStore = fakeTokenStore()
    val clickId = buildStartClickId("token-A")

    resolver.resolve(clickId, tokenStore, hasDefaultProject = true, repository)
    // Second delivery of the exact same click id, as if the host redelivered a stale
    // lastClickableId on a later, non-tap onTileRequest.
    resolver.resolve(clickId, tokenStore, hasDefaultProject = true, repository)

    assertEquals(1, startCalls)
  }

  @Test
  fun `replayed stop click id calls stopTimer exactly once`() = runTest {
    var stopCalls = 0
    val repository =
      TileActionRepository(
        fetchRunningEntry = { ClockifyResult.Success(entry("e1")) },
        startTimer = { ClockifyResult.Success(entry("e1")) },
        stopTimer = {
          stopCalls++
          ClockifyResult.Success(Unit)
        },
      )
    val resolver = TileClickResolver()
    val tokenStore = fakeTokenStore()
    val clickId = buildStopClickId("token-B")

    resolver.resolve(clickId, tokenStore, hasDefaultProject = true, repository)
    resolver.resolve(clickId, tokenStore, hasDefaultProject = true, repository)

    assertEquals(1, stopCalls)
  }

  @Test
  fun `concurrent resolutions of the same click id call startTimer exactly once`() = runTest {
    var startCalls = 0
    val consumed = mutableListOf<String>()
    // The "seen" read happens BEFORE gate.await(), not after: this is what gives the gate any
    // power to distinguish "the Mutex serializes resolve() calls" from "it doesn't". If both
    // coroutines could reach this lambda concurrently, they'd both read consumed.contains(token)
    // as false before either awaits the gate, and both would go on to call startTimer once
    // released - proving the Mutex is required. (Reading `consumed` only after the await, as an
    // earlier version of this test did, is tautological: under the single-threaded test
    // dispatcher, whichever coroutine resumes first from the gate then runs uninterrupted through
    // markConsumed/startTimer with no further suspension point, so the second coroutine always
    // observes "already consumed" by the time it gets to read - true whether or not the Mutex is
    // even there.) With the Mutex, job2 can't call this lambda at all until job1's entire
    // resolve() - including startTimer - has completed and released the lock, so job2's "seen" is
    // read only after job1 already marked the token consumed.
    val gate = CompletableDeferred<Unit>()
    val tokenStore =
      TileClickTokenStore(
        isConsumed = { token ->
          val seen = consumed.contains(token)
          gate.await()
          seen
        },
        markConsumed = { token -> consumed.add(token) },
      )
    val repository =
      TileActionRepository(
        fetchRunningEntry = { ClockifyResult.Success(null) },
        startTimer = {
          startCalls++
          ClockifyResult.Success(entry("e1"))
        },
        stopTimer = { ClockifyResult.Success(Unit) },
      )
    val resolver = TileClickResolver()
    val clickId = buildStartClickId("token-C")

    val job1 = launch {
      resolver.resolve(clickId, tokenStore, hasDefaultProject = true, repository)
    }
    val job2 = launch {
      resolver.resolve(clickId, tokenStore, hasDefaultProject = true, repository)
    }
    runCurrent() // let both reach the gate (or, with the Mutex, let the second block on the lock)
    gate.complete(Unit)
    job1.join()
    job2.join()

    assertEquals(1, startCalls)
  }

  @Test
  fun `stop click calls stopTimer without depending on a fetch, even if one would fail`() =
    runTest {
      var stopCalls = 0
      var fetchCalls = 0
      val repository =
        TileActionRepository(
          fetchRunningEntry = {
            fetchCalls++
            ClockifyResult.Failure(ClockifyError.Offline)
          },
          startTimer = { ClockifyResult.Success(entry("e1")) },
          stopTimer = {
            stopCalls++
            ClockifyResult.Success(Unit)
          },
        )
      val resolver = TileClickResolver()

      val outcome =
        resolver.resolve(
          buildStopClickId("token-D"),
          fakeTokenStore(),
          hasDefaultProject = true,
          repository,
        )

      assertEquals(1, stopCalls)
      assertEquals(0, fetchCalls)
      assertEquals(TileRunningState.Confirmed(null), outcome.runningState)
    }

  @Test
  fun `start click skips startTimer when a fetch confirms a timer is already running`() = runTest {
    var startCalls = 0
    val alreadyRunning = entry("already-running")
    val repository =
      TileActionRepository(
        fetchRunningEntry = { ClockifyResult.Success(alreadyRunning) },
        startTimer = {
          startCalls++
          ClockifyResult.Success(entry("new"))
        },
        stopTimer = { ClockifyResult.Success(Unit) },
      )
    val resolver = TileClickResolver()

    val outcome =
      resolver.resolve(
        buildStartClickId("token-E"),
        fakeTokenStore(),
        hasDefaultProject = true,
        repository,
      )

    assertEquals(0, startCalls)
    assertEquals(TileRunningState.Confirmed(alreadyRunning), outcome.runningState)
  }

  @Test
  fun `a routine refresh with a failing fetch reports Unknown, not confirmed idle`() = runTest {
    val repository =
      TileActionRepository(
        fetchRunningEntry = { ClockifyResult.Failure(ClockifyError.Offline) },
        startTimer = { ClockifyResult.Success(entry("e1")) },
        stopTimer = { ClockifyResult.Success(Unit) },
      )
    val resolver = TileClickResolver()

    // No click id: this is what a periodic freshness-interval refresh or TileUpdater.refresh()
    // delivers, as opposed to a tap.
    val outcome = resolver.resolve(null, fakeTokenStore(), hasDefaultProject = true, repository)

    assertEquals(TileRunningState.Unknown, outcome.runningState)
    assertFalse(outcome.actionFailed)
  }

  @Test
  fun `a failed start surfaces actionFailed`() = runTest {
    val repository =
      TileActionRepository(
        fetchRunningEntry = { ClockifyResult.Success(null) },
        startTimer = { ClockifyResult.Failure(ClockifyError.Offline) },
        stopTimer = { ClockifyResult.Success(Unit) },
      )
    val resolver = TileClickResolver()

    val outcome =
      resolver.resolve(
        buildStartClickId("token-F"),
        fakeTokenStore(),
        hasDefaultProject = true,
        repository,
      )

    assertTrue(outcome.actionFailed)
  }

  @Test
  fun `start click never calls startTimer when the pre-check fetch fails`() = runTest {
    var startCalls = 0
    var fetchCalls = 0
    val repository =
      TileActionRepository(
        fetchRunningEntry = {
          fetchCalls++
          ClockifyResult.Failure(ClockifyError.Offline)
        },
        startTimer = {
          startCalls++
          ClockifyResult.Success(entry("e1"))
        },
        stopTimer = { ClockifyResult.Success(Unit) },
      )
    val resolver = TileClickResolver()

    val outcome =
      resolver.resolve(
        buildStartClickId("token-G"),
        fakeTokenStore(),
        hasDefaultProject = true,
        repository,
      )

    // A failed pre-check fetch can't tell whether something is already running (e.g. started from
    // the phone moments ago), so startTimer - which stops-then-starts - must never be attempted;
    // a refused tap with visible actionFailed feedback is the safe outcome here, not a silent
    // clobber.
    assertEquals(0, startCalls)
    assertEquals(1, fetchCalls)
    assertEquals(TileRunningState.Unknown, outcome.runningState)
    assertTrue(outcome.actionFailed)
  }
}
