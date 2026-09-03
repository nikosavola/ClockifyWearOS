package fi.nikosavola.clockifywear.companion

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import fi.nikosavola.clockifywear.ClockifyApp
import kotlinx.coroutines.runBlocking

private const val TAG = "ApiKeyMessageListener"

/**
 * Receives a sign-in request pushed by the phone companion app and resolves it through
 * [ApiKeyMessageResolver], then replies to the requesting node. See the `companion-protocol` module
 * for the wire contract shared with the mobile module.
 */
class ApiKeyMessageListenerService : WearableListenerService() {
  private val resolver = ApiKeyMessageResolver()

  // WearableListenerService dispatches this off the main thread, so blocking here for a sign-in
  // network round trip doesn't freeze the UI - it does serialize any other message behind it,
  // which is accepted here given how rarely more than one companion request happens in quick
  // succession.
  override fun onMessageReceived(messageEvent: MessageEvent) {
    val requestId = requestIdFromApiKeyRequestPath(messageEvent.path) ?: return

    val apiKey = String(messageEvent.data, Charsets.UTF_8)
    val appContainer = (application as ClockifyApp).appContainer
    val outcome = runBlocking {
      // Must complete before touching SettingsStore: AppContainer's lazy init kicks off this
      // same priming read on cold start, and racing it against signIn's own cachedApiKey write
      // below can let the priming read's stale snapshot clobber the just-written key back to
      // null - see AppContainer.settingsPrimed's doc, which is exactly why every ViewModel
      // awaits it first before its own first repository call.
      appContainer.settingsPrimed.await()
      resolver.resolve(
        apiKey,
        isAlreadySignedIn = { appContainer.settingsStore.currentSettings().workspaceId != null },
        signIn = appContainer.repository::signIn,
      )
    }

    if (outcome is CompanionSignInOutcome.Success) {
      // The Settings screen's own sign-in path refreshes these via SettingsViewModel's
      // onSignedOut/NavGraph wiring; this listener bypasses that screen entirely, so without this
      // call the tile/complication would keep showing "sign in required" until their own
      // freshness-interval refresh happens to fire.
      appContainer.tileUpdater.refresh()
    }

    reply(messageEvent.sourceNodeId, requestId, outcome)
  }

  private fun reply(sourceNodeId: String, requestId: String, outcome: CompanionSignInOutcome) {
    Wearable.getMessageClient(this)
      .sendMessage(sourceNodeId, outcome.toReplyPath(requestId), outcome.toReplyPayload())
      .addOnFailureListener { e -> Log.w(TAG, "Failed to send sign-in reply to $sourceNodeId", e) }
  }
}
