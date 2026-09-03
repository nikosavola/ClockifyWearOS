package fi.nikosavola.clockifywear.mobile

import androidx.lifecycle.ViewModelStore
import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val API_KEY = "secret-key"
private const val TEST_TIMEOUT_MILLIS = 100L

// Plain JVM unit test: SignInViewModel takes an injected WatchLinkClient, no Android/Robolectric
// dependency needed - mirrors SettingsViewModelTest's Dispatchers.setMain(testDispatcher) +
// runTest(testDispatcher) pairing so viewModelScope shares the test's virtual clock.
class SignInViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `apiKeyInput starts empty and reflects updateApiKeyInput`() =
    runTest(testDispatcher) {
      val viewModel = SignInViewModel(FakeWatchLinkClient())

      assertEquals("", viewModel.apiKeyInput.value)
      viewModel.updateApiKeyInput("pasted-key")

      assertEquals("pasted-key", viewModel.apiKeyInput.value)
    }

  @Test
  fun `no reachable watch reports NoWatchFound without sending anything`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient(nodeId = null)
      val viewModel = SignInViewModel(client)

      viewModel.sendApiKey(API_KEY).join()

      assertEquals(SignInUiState.NoWatchFound, viewModel.uiState.value)
      assertNull(client.sentApiKey)
    }

  @Test
  fun `successful ack reports the account email`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient()
      val viewModel = SignInViewModel(client)

      val job = viewModel.sendApiKey(API_KEY)
      // runCurrent(), not advanceUntilIdle(): the latter would also run the timeout's delay() to
      // completion since nothing else is scheduled to unblock it yet, racing this test's own
      // emitAck against the timeout instead of letting the ack win as intended.
      runCurrent()
      assertEquals(SignInUiState.WaitingForWatch, viewModel.uiState.value)
      client.emitAck(SignInAck.Success(client.lastRequestId!!, "user@example.com"))
      job.join()

      assertEquals(API_KEY, client.sentApiKey)
      assertEquals(SignInUiState.Success("user@example.com"), viewModel.uiState.value)
    }

  @Test
  fun `failure ack reports the error code`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient()
      val viewModel = SignInViewModel(client)

      val job = viewModel.sendApiKey(API_KEY)
      runCurrent()
      client.emitAck(
        SignInAck.Failure(client.lastRequestId!!, CompanionSignInErrorCode.UNAUTHORIZED)
      )
      job.join()

      assertEquals(
        SignInUiState.Failure(CompanionSignInErrorCode.UNAUTHORIZED),
        viewModel.uiState.value,
      )
    }

  @Test
  fun `an ack for a different request id is ignored`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient()
      val viewModel = SignInViewModel(client, resultTimeoutMillis = TEST_TIMEOUT_MILLIS)

      val job = viewModel.sendApiKey(API_KEY)
      runCurrent()
      // Simulates a stale reply to an earlier, abandoned attempt (or, absent request ids
      // entirely, to a completely different attempt) arriving during this one.
      client.emitAck(SignInAck.Success("some-other-request-id", "wrong@example.com"))
      runCurrent()
      assertEquals(SignInUiState.WaitingForWatch, viewModel.uiState.value)

      advanceTimeBy(TEST_TIMEOUT_MILLIS + 1)
      job.join()

      assertEquals(SignInUiState.Timeout, viewModel.uiState.value)
    }

  @Test
  fun `no ack within the timeout reports Timeout`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient()
      val viewModel = SignInViewModel(client, resultTimeoutMillis = TEST_TIMEOUT_MILLIS)

      val job = viewModel.sendApiKey(API_KEY)
      advanceTimeBy(TEST_TIMEOUT_MILLIS + 1)
      job.join()

      assertEquals(SignInUiState.Timeout, viewModel.uiState.value)
    }

  @Test
  fun `failed send reports SendFailed instead of waiting for an ack`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient(sendSucceeds = false)
      val viewModel = SignInViewModel(client)

      viewModel.sendApiKey(API_KEY).join()

      assertEquals(SignInUiState.SendFailed, viewModel.uiState.value)
    }

  @Test
  fun `a second call while one is already in flight reuses the same job`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient()
      val viewModel = SignInViewModel(client)

      val first = viewModel.sendApiKey(API_KEY)
      val second = viewModel.sendApiKey(API_KEY)

      assertSame(first, second)
    }

  @Test
  fun `onCleared closes the watch link client`() =
    runTest(testDispatcher) {
      val client = FakeWatchLinkClient()
      val viewModel = SignInViewModel(client)
      val viewModelStore = ViewModelStore().apply { put("signIn", viewModel) }

      viewModelStore.clear()

      assertTrue(client.closed)
    }
}
