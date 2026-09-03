package fi.nikosavola.clockifywear.mobile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory [WatchLinkClient] for [SignInViewModelTest] and [SignInScreenTest]; no Play Services
 * involved.
 *
 * @param nodeId what [findReachableWatchNode] returns; null simulates no watch found.
 * @param sendSucceeds whether [sendApiKey] reports success.
 * @param autoAck if set, [sendApiKey] emits `autoAck(requestId)` (when non-null) as soon as it's
 *   called - safe to rely on because [SignInViewModel] starts collecting [signInResults] with
 *   `CoroutineStart.UNDISPATCHED` before ever calling [sendApiKey], so a subscriber is already
 *   attached. Leave null (the default) to control acks manually via [emitAck] instead.
 */
class FakeWatchLinkClient(
  private val nodeId: String? = "node-1",
  private val sendSucceeds: Boolean = true,
  private val autoAck: ((requestId: String) -> SignInAck?)? = null,
) : WatchLinkClient {
  var sentApiKey: String? = null
    private set

  var sentToNodeId: String? = null
    private set

  /** The request id [SignInViewModel] generated for the most recent [sendApiKey] call. */
  var lastRequestId: String? = null
    private set

  var closed: Boolean = false
    private set

  private val mutableSignInResults = MutableSharedFlow<SignInAck>(extraBufferCapacity = 1)
  override val signInResults: SharedFlow<SignInAck> = mutableSignInResults.asSharedFlow()

  override suspend fun findReachableWatchNode(): String? = nodeId

  override suspend fun sendApiKey(nodeId: String, apiKey: String, requestId: String): Boolean {
    lastRequestId = requestId
    if (!sendSucceeds) return false
    sentToNodeId = nodeId
    sentApiKey = apiKey
    autoAck?.invoke(requestId)?.let { mutableSignInResults.emit(it) }
    return true
  }

  override fun close() {
    closed = true
  }

  suspend fun emitAck(ack: SignInAck) {
    mutableSignInResults.emit(ack)
  }
}
