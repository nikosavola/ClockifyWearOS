package fi.nikosavola.clockifywear.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode
import fi.nikosavola.clockifywear.companion.newRequestId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The watch's own sign-in call can make up to two sequential HTTP requests (ClockifyRepository.
// signIn -> persistResolvedWorkspace -> resolveWorkspaceId falls back to a second getWorkspaces()
// call when the account has no active/default workspace), each independently bounded by OkHttp's
// 10s connect + 30s read timeouts and RetryOn429Interceptor's one built-in retry (+ up to 10s
// sleep) on a 429. The true worst case is close to (40s + 10s + 40s) x 2 =~ 180s. 90s is a
// deliberate practical compromise, not that real worst case: it comfortably covers the common
// path (a handful of seconds) and a single stalled/retried request, while accepting that the rare
// double-429-and-no-workspace combination can still show Timeout while the watch is still working
// - the request id in SignInAck means a late reply in that case is correctly ignored rather than
// misattributed to a later retry, so this is a UX compromise, not a correctness one.
private const val DEFAULT_RESULT_TIMEOUT_MILLIS = 90_000L

sealed interface SignInUiState {
  data object Idle : SignInUiState

  data object Sending : SignInUiState

  data object WaitingForWatch : SignInUiState

  data class Success(val email: String?) : SignInUiState

  data class Failure(val errorCode: CompanionSignInErrorCode) : SignInUiState

  data object Timeout : SignInUiState

  data object NoWatchFound : SignInUiState

  data object SendFailed : SignInUiState
}

class SignInViewModel(
  private val watchLinkClient: WatchLinkClient,
  private val resultTimeoutMillis: Long = DEFAULT_RESULT_TIMEOUT_MILLIS,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
  val uiState: StateFlow<SignInUiState> = mutableUiState.asStateFlow()

  // Guards against a double-tap launching two concurrent attempts (two collectors on
  // signInResults, interleaved state writes) - same LAZY-plus-assign-before-start pattern as
  // TimerViewModel.start(), for the same reason: an eagerly-launched coroutine on
  // Dispatchers.Main.immediate can run synchronously up to its first suspension point before a
  // trailing `.also { attemptJob = it }` would ever execute.
  private var attemptJob: Job? = null

  /** Returns the launched [Job] so tests can `join()` it instead of racing real I/O. */
  fun sendApiKey(apiKey: String): Job {
    attemptJob?.let { if (it.isActive) return it }
    val job =
      viewModelScope.launch(start = CoroutineStart.LAZY) {
        mutableUiState.value = SignInUiState.Sending
        val nodeId = watchLinkClient.findReachableWatchNode()
        if (nodeId != null) {
          signInOnNode(nodeId, apiKey)
        } else {
          mutableUiState.value = SignInUiState.NoWatchFound
        }
      }
    attemptJob = job
    job.start()
    return job
  }

  private suspend fun signInOnNode(nodeId: String, apiKey: String) {
    mutableUiState.value = SignInUiState.WaitingForWatch
    val requestId = newRequestId()
    // UNDISPATCHED so this starts collecting signInResults synchronously, before sendApiKey runs
    // below - otherwise a fast watch's reply could arrive before this coroutine ever got
    // scheduled to start listening for it.
    val ackDeferred =
      viewModelScope.async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(resultTimeoutMillis) {
          watchLinkClient.signInResults.first { it.requestId == requestId }
        }
      }
    val sent = watchLinkClient.sendApiKey(nodeId, apiKey, requestId)
    if (sent) {
      mutableUiState.value = toUiState(ackDeferred.await())
    } else {
      ackDeferred.cancel()
      mutableUiState.value = SignInUiState.SendFailed
    }
  }

  private fun toUiState(ack: SignInAck?): SignInUiState =
    when (ack) {
      is SignInAck.Success -> SignInUiState.Success(ack.email)
      is SignInAck.Failure -> SignInUiState.Failure(ack.errorCode)
      null -> SignInUiState.Timeout
    }

  override fun onCleared() {
    watchLinkClient.close()
  }
}
