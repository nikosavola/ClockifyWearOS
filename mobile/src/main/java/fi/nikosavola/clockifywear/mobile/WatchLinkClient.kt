package fi.nikosavola.clockifywear.mobile

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode
import fi.nikosavola.clockifywear.companion.WATCH_CAPABILITY
import fi.nikosavola.clockifywear.companion.apiKeyRequestPath
import fi.nikosavola.clockifywear.companion.requestIdFromSignInFailurePath
import fi.nikosavola.clockifywear.companion.requestIdFromSignInSuccessPath
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await

/**
 * The watch's reply to a sign-in request, tagged with the [requestId] it answers - see
 * `companion-protocol`'s `CompanionProtocol.kt` for why a bare success/failure isn't enough:
 * without this, a slow reply to an abandoned/timed-out attempt could be mistaken for the response
 * to a later retry.
 */
sealed interface SignInAck {
  val requestId: String

  data class Success(override val requestId: String, val email: String?) : SignInAck

  data class Failure(override val requestId: String, val errorCode: CompanionSignInErrorCode) :
    SignInAck
}

/**
 * What [SignInViewModel] needs to reach a paired watch, kept as an interface so the ViewModel is
 * unit-testable with a fake instead of the real Play Services Wearable APIs.
 */
interface WatchLinkClient : AutoCloseable {
  /** Hot: starts receiving replies as soon as this client is constructed, not on first collect. */
  val signInResults: SharedFlow<SignInAck>

  /**
   * Node id of a *nearby* (directly Bluetooth-connected, not just cloud-reachable) watch running
   * the wear app, or null if none is found - including when the lookup itself fails (e.g. Play
   * Services unreachable), which isn't worth distinguishing from "no watch" in the UI.
   */
  suspend fun findReachableWatchNode(): String?

  /** False if the message couldn't even be handed to Play Services for delivery. */
  suspend fun sendApiKey(nodeId: String, apiKey: String, requestId: String): Boolean

  override fun close() {}
}

/** Production [WatchLinkClient], backed by the real Play Services Wearable Data Layer APIs. */
class PlayServicesWatchLinkClient(context: Context) : WatchLinkClient {
  private val capabilityClient = Wearable.getCapabilityClient(context)
  private val messageClient = Wearable.getMessageClient(context)

  // extraBufferCapacity = 1 (not replay): a reply is only ever dropped if it arrives while
  // absolutely nobody has ever collected signInResults yet - SignInViewModel guards against that
  // by starting its collector with CoroutineStart.UNDISPATCHED before calling sendApiKey, not by
  // relying on this buffer. What extraBufferCapacity actually buys here is a slow-but-already-
  // subscribed collector not blocking this listener's emit; replay = 0 (not 1) still means a
  // second sign-in attempt's collector never sees a stale ack left over from a previous one - and
  // even if it did, the requestId in that stale ack wouldn't match the new attempt's.
  private val mutableSignInResults = MutableSharedFlow<SignInAck>(extraBufferCapacity = 1)
  override val signInResults: SharedFlow<SignInAck> = mutableSignInResults.asSharedFlow()

  private val listener = MessageClient.OnMessageReceivedListener { event ->
    parseAck(event.path, event.data)?.let { mutableSignInResults.tryEmit(it) }
  }

  init {
    messageClient.addListener(listener)
  }

  override suspend fun findReachableWatchNode(): String? =
    try {
      capabilityClient
        .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        .await()
        .nodes
        .firstOrNull { it.isNearby }
        ?.id
    } catch (e: ApiException) {
      null
    }

  override suspend fun sendApiKey(nodeId: String, apiKey: String, requestId: String): Boolean =
    try {
      messageClient
        .sendMessage(nodeId, apiKeyRequestPath(requestId), apiKey.toByteArray(Charsets.UTF_8))
        .await()
      true
    } catch (e: ApiException) {
      false
    }

  override fun close() {
    messageClient.removeListener(listener)
  }

  private fun parseAck(path: String, data: ByteArray): SignInAck? {
    val successRequestId = requestIdFromSignInSuccessPath(path)
    val failureRequestId = requestIdFromSignInFailurePath(path)
    return when {
      successRequestId != null ->
        SignInAck.Success(successRequestId, String(data, Charsets.UTF_8).ifEmpty { null })
      failureRequestId != null ->
        SignInAck.Failure(
          failureRequestId,
          CompanionSignInErrorCode.fromWireValue(String(data, Charsets.UTF_8)),
        )
      else -> null
    }
  }
}
