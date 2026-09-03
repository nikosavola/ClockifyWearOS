package fi.nikosavola.clockifywear.mobile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-memory [WatchLinkClient] for [SignInViewModelTest]; no Play Services involved. */
class FakeWatchLinkClient(
  private val nodeId: String? = "node-1",
  private val sendSucceeds: Boolean = true,
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
    return true
  }

  override fun close() {
    closed = true
  }

  suspend fun emitAck(ack: SignInAck) {
    mutableSignInResults.emit(ack)
  }
}
