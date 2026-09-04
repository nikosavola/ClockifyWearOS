package fi.nikosavola.clockifywear.companion

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import fi.nikosavola.clockifywear.ClockifyApp
import kotlinx.coroutines.runBlocking

private const val TAG = "ApiKeyMessageListener"

/**
 * Receives a sign-in request pushed by the phone companion app and replies to the requesting node.
 * The actual logic lives in [ApiKeyMessageDispatcher] (see its KDoc for why this class is kept this
 * thin: none of it can be exercised without a real Android `Service`, so it's covered by manual
 * QA - see `docs/COMPANION_QA.md` - rather than a unit test of this class itself).
 */
class ApiKeyMessageListenerService : WearableListenerService() {
  private val dispatcher = ApiKeyMessageDispatcher()

  // WearableListenerService dispatches this off the main thread, so blocking here for a sign-in
  // network round trip doesn't freeze the UI - it does serialize any other message behind it,
  // which is accepted here given how rarely more than one companion request happens in quick
  // succession.
  override fun onMessageReceived(messageEvent: MessageEvent) {
    val appContainer = (application as ClockifyApp).appContainer
    val environment =
      CompanionSignInEnvironment(
        awaitSettingsPrimed = { appContainer.settingsPrimed.await() },
        isAlreadySignedIn = { appContainer.settingsStore.currentSettings().workspaceId != null },
        signIn = appContainer.repository::signIn,
        onSignedIn = appContainer.tileUpdater::refresh,
      )
    val reply =
      runBlocking { dispatcher.dispatch(messageEvent.path, messageEvent.data, environment) }
        ?: return

    Wearable.getMessageClient(this)
      .sendMessage(messageEvent.sourceNodeId, reply.path, reply.payload)
      .addOnFailureListener { e ->
        Log.w(TAG, "Failed to send sign-in reply to ${messageEvent.sourceNodeId}", e)
      }
  }
}
