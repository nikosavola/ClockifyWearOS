package fi.nikosavola.clockifywear.companion

import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.UserDto

/**
 * Everything a [ApiKeyMessageDispatcher.dispatch] call needs from `AppContainer`, bundled to keep
 * its parameter list short - same pattern as
 * [fi.nikosavola.clockifywear.tile.TileActionRepository].
 */
class CompanionSignInEnvironment(
  val awaitSettingsPrimed: suspend () -> Unit,
  val isAlreadySignedIn: suspend () -> Boolean,
  val signIn: suspend (String) -> ClockifyResult<UserDto>,
  val onSignedIn: () -> Unit,
)

/**
 * A message to send back to the requesting node. Not a `data class`: [payload] is a [ByteArray],
 * whose generated `equals`/`hashCode` would compare references, not content - tests compare [path]
 * and [payload] separately instead of relying on structural equality here.
 */
class CompanionReply(val path: String, val payload: ByteArray)

/**
 * Everything [ApiKeyMessageListenerService.onMessageReceived] does that isn't itself an Android
 * `Service`/`MessageClient` call, kept separate so it's testable without a real
 * `WearableListenerService` - mirrors [ApiKeyMessageResolver]'s own split from the service one
 * level down.
 */
class ApiKeyMessageDispatcher(
  private val resolver: ApiKeyMessageResolver = ApiKeyMessageResolver()
) {
  /** Null if [path] isn't a sign-in request this app answers - the caller sends nothing back. */
  suspend fun dispatch(
    path: String,
    data: ByteArray,
    env: CompanionSignInEnvironment,
  ): CompanionReply? {
    val requestId = requestIdFromApiKeyRequestPath(path) ?: return null

    val apiKey = String(data, Charsets.UTF_8)
    // Must complete before touching SettingsStore: AppContainer's lazy init kicks off this same
    // priming read on cold start, and racing it against signIn's own cachedApiKey write below can
    // let the priming read's stale snapshot clobber the just-written key back to null - see
    // AppContainer.settingsPrimed's doc, which is exactly why every ViewModel awaits it first
    // before its own first repository call.
    env.awaitSettingsPrimed()
    val outcome = resolver.resolve(apiKey, env.isAlreadySignedIn, env.signIn)

    if (outcome is CompanionSignInOutcome.Success) {
      // The Settings screen's own sign-in path refreshes these via SettingsViewModel's
      // onSignedOut/NavGraph wiring; this dispatcher bypasses that screen entirely, so without
      // this call the tile/complication would keep showing "sign in required" until their own
      // freshness-interval refresh happens to fire.
      env.onSignedIn()
    }

    return CompanionReply(outcome.toReplyPath(requestId), outcome.toReplyPayload())
  }
}
