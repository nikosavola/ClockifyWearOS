package fi.nikosavola.clockifywear.tile

import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val EDGE_BUTTON_START_CLICK_ID = "start_timer"
private const val EDGE_BUTTON_STOP_CLICK_ID = "stop_timer"
private const val CLICK_ID_TOKEN_SEPARATOR = ":"

/** Builds this build's one-shot edge button click id; pair with [TileClickResolver.resolve]. */
fun buildStartClickId(token: String): String =
  "$EDGE_BUTTON_START_CLICK_ID$CLICK_ID_TOKEN_SEPARATOR$token"

fun buildStopClickId(token: String): String =
  "$EDGE_BUTTON_STOP_CLICK_ID$CLICK_ID_TOKEN_SEPARATOR$token"

/** What the tile actually knows about the running entry after resolving a request. */
sealed interface TileRunningState {
  data class Confirmed(val entry: TimeEntryDto?) : TileRunningState

  /** The fetch itself failed (offline/unauthorized/rate-limited) - distinct from confirmed idle. */
  data object Unknown : TileRunningState
}

data class TileClickOutcome(val runningState: TileRunningState, val actionFailed: Boolean = false)

/** Durable record of which click token was last acted on; see [TileClickResolver]'s doc comment. */
class TileClickTokenStore(
  val isConsumed: suspend (String) -> Boolean,
  val markConsumed: suspend (String) -> Unit,
)

/**
 * The repository calls a click resolution may need, bundled to keep [TileClickResolver.resolve]'s
 * parameter list short.
 */
class TileActionRepository(
  val fetchRunningEntry: suspend () -> ClockifyResult<TimeEntryDto?>,
  val startTimer: suspend () -> ClockifyResult<TimeEntryDto>,
  val stopTimer: suspend () -> ClockifyResult<Unit>,
)

/**
 * Resolves what an `onTileRequest` should do with `currentState.lastClickableId`, and performs the
 * corresponding start/stop call at most once per click.
 *
 * Click ids are one-shot by construction: the caller embeds a fresh token on every tile rebuild
 * (see [buildStartClickId]/[buildStopClickId]), and [TileClickTokenStore] tracks which tokens have
 * already been acted on. This defends against the host redelivering a stale `lastClickableId` on a
 * later, non-tap request (a freshness-interval refresh, or `TileUpdater.refresh()`) whose current
 * server-side state happens to match the stale click's direction again - undocumented host behavior
 * this class does not rely on either way: a token is usable exactly once no matter how many times
 * it gets redelivered. [mutex] additionally serializes concurrent calls carrying the same click id
 * (a double-tap before the tile has rebuilt), so only one of them ever gets past the
 * consumed-check.
 */
class TileClickResolver(private val mutex: Mutex = Mutex()) {
  suspend fun resolve(
    lastClickableId: String?,
    tokenStore: TileClickTokenStore,
    hasDefaultProject: Boolean,
    repository: TileActionRepository,
  ): TileClickOutcome = mutex.withLock {
    val click = parseClickId(lastClickableId)
    if (click == null) {
      TileClickOutcome(fetchState(repository))
    } else {
      resolveClick(click, tokenStore, hasDefaultProject, repository)
    }
  }

  private suspend fun resolveClick(
    click: ParsedClick,
    tokenStore: TileClickTokenStore,
    hasDefaultProject: Boolean,
    repository: TileActionRepository,
  ): TileClickOutcome {
    if (tokenStore.isConsumed(click.token)) return TileClickOutcome(fetchState(repository))
    // Marked before the mutation runs: a crash or process death mid-mutation must not leave this
    // token processable again, which could otherwise double-fire the start/stop on retry.
    tokenStore.markConsumed(click.token)
    return when (click.direction) {
      Direction.STOP -> resolveStop(repository)
      Direction.START -> resolveStart(hasDefaultProject, repository)
    }
  }

  // stopTimer() is safe to call unconditionally: the repository treats "nothing running" as a
  // successful no-op, so this doesn't need to be gated behind a prior fetch.
  private suspend fun resolveStop(repository: TileActionRepository): TileClickOutcome =
    when (repository.stopTimer()) {
      is ClockifyResult.Success -> TileClickOutcome(TileRunningState.Confirmed(null))
      is ClockifyResult.Failure -> TileClickOutcome(TileRunningState.Unknown, actionFailed = true)
    }

  // Unlike stopTimer(), startTimer() is not a safe unconditional call: it stops whatever's
  // running before starting the new entry (see startAfterStopping), so an already-running entry -
  // e.g. started from the phone moments ago - would be clobbered. Fetch first and skip the start
  // if something is genuinely already running. A failed fetch refuses the start rather than
  // attempting it anyway: we can't tell whether something is running, and treating "fetch failed"
  // as "confirmed nothing running" is exactly the clobbering this guards against, just from the
  // other direction. A refused tap with visible actionFailed feedback beats silently destroying a
  // phone-started entry.
  private suspend fun resolveStart(
    hasDefaultProject: Boolean,
    repository: TileActionRepository,
  ): TileClickOutcome {
    if (!hasDefaultProject) return TileClickOutcome(fetchState(repository))
    return when (val fetchResult = repository.fetchRunningEntry()) {
      is ClockifyResult.Success ->
        fetchResult.value?.let { TileClickOutcome(TileRunningState.Confirmed(it)) }
          ?: performStart(repository)
      is ClockifyResult.Failure -> TileClickOutcome(TileRunningState.Unknown, actionFailed = true)
    }
  }

  private suspend fun performStart(repository: TileActionRepository): TileClickOutcome =
    when (val result = repository.startTimer()) {
      is ClockifyResult.Success -> TileClickOutcome(TileRunningState.Confirmed(result.value))
      is ClockifyResult.Failure -> TileClickOutcome(TileRunningState.Unknown, actionFailed = true)
    }

  private suspend fun fetchState(repository: TileActionRepository): TileRunningState =
    when (val result = repository.fetchRunningEntry()) {
      is ClockifyResult.Success -> TileRunningState.Confirmed(result.value)
      is ClockifyResult.Failure -> TileRunningState.Unknown
    }

  private enum class Direction {
    START,
    STOP,
  }

  private data class ParsedClick(val direction: Direction, val token: String)

  private fun parseClickId(clickId: String?): ParsedClick? = clickId?.let { id ->
    val separatorIndex = id.indexOf(CLICK_ID_TOKEN_SEPARATOR)
    val token = if (separatorIndex >= 0) id.substring(separatorIndex + 1) else ""
    val direction =
      if (separatorIndex >= 0) directionForPrefix(id.substring(0, separatorIndex)) else null
    direction?.takeIf { token.isNotEmpty() }?.let { ParsedClick(it, token) }
  }

  private fun directionForPrefix(prefix: String): Direction? =
    when (prefix) {
      EDGE_BUTTON_START_CLICK_ID -> Direction.START
      EDGE_BUTTON_STOP_CLICK_ID -> Direction.STOP
      else -> null
    }
}
