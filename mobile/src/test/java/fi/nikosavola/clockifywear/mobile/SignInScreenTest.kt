package fi.nikosavola.clockifywear.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val WAIT_TIMEOUT_MS = 5_000L
private const val SHORT_TIMEOUT_MILLIS = 200L

// Deliberately not using Dispatchers.setMain(StandardTestDispatcher()) here, unlike
// SignInViewModelTest: createComposeRule() needs the real main looper, and overriding Main would
// deadlock viewModelScope. composeRule.waitUntil + a short resultTimeoutMillis stand in for
// virtual-time control instead.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInScreenTest {
  @get:Rule val composeRule = createComposeRule()

  private fun string(@StringRes resId: Int, vararg formatArgs: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId, *formatArgs)

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun setContent(viewModel: SignInViewModel) {
    composeRule.setContent { CompanionTheme { SignInScreen(viewModel) } }
  }

  @Test
  fun `idle screen shows the title and a disabled sign-in button`() {
    setContent(SignInViewModel(FakeWatchLinkClient()))

    waitForText(string(R.string.sign_in_title))
    waitForText(string(R.string.sign_in_api_key_label))
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).assertIsNotEnabled()
  }

  @Test
  fun `entering an api key enables the sign-in button`() {
    setContent(SignInViewModel(FakeWatchLinkClient()))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("secret")

    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).assertIsEnabled()
  }

  @Test
  fun `whitespace-only input leaves the sign-in button disabled`() {
    setContent(SignInViewModel(FakeWatchLinkClient()))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("   ")

    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).assertIsNotEnabled()
  }

  @Test
  fun `the api key is trimmed before being sent`() {
    // A short timeout, not the 90s default: nothing here waits for the final Timeout state, but a
    // dangling real-time wait would otherwise keep this coroutine alive well past the test.
    val client = FakeWatchLinkClient()
    setContent(SignInViewModel(client, resultTimeoutMillis = SHORT_TIMEOUT_MILLIS))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("  key  ")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    // Checking the fake directly, not a UI state: "Sending" is set then immediately superseded
    // (WaitingForWatch, then eventually Timeout), too transient to reliably catch as text.
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { client.sentApiKey != null }
    assert(client.sentApiKey == "key") { "expected trimmed key, got ${client.sentApiKey}" }
  }

  @Test
  fun `paste button fills the field from the system clipboard`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val systemClipboard = context.getSystemService(ClipboardManager::class.java)
    systemClipboard.setPrimaryClip(ClipData.newPlainText("api key", "clipboard-key"))
    setContent(SignInViewModel(FakeWatchLinkClient()))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNodeWithText(string(R.string.sign_in_paste_button)).performClick()

    // The paste button reads the clipboard through the newer suspend LocalClipboard API inside a
    // launched coroutine, unlike the watch's synchronous LocalClipboardManager - an immediate
    // assertion would race that coroutine, so wait for it to land instead. onNodeWithText matches
    // against the field's semantics text, which PasswordVisualTransformation doesn't affect - only
    // the rendered glyphs are masked.
    composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
      composeRule.onAllNodesWithText("clipboard-key").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasSetTextAction()).assertTextContains("clipboard-key")
  }

  @Test
  fun `no reachable watch shows the no-watch-found message`() {
    setContent(SignInViewModel(FakeWatchLinkClient(nodeId = null)))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_status_no_watch))
  }

  @Test
  fun `a failed send shows the send-failed message`() {
    setContent(SignInViewModel(FakeWatchLinkClient(sendSucceeds = false)))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_status_send_failed))
  }

  @Test
  fun `a successful ack with an email shows the account email`() {
    val client = FakeWatchLinkClient(autoAck = { id -> SignInAck.Success(id, "user@example.com") })
    setContent(SignInViewModel(client))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_status_success, "user@example.com"))
  }

  @Test
  fun `a successful ack with no email shows the no-email success message`() {
    val client = FakeWatchLinkClient(autoAck = { id -> SignInAck.Success(id, null) })
    setContent(SignInViewModel(client))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_status_success_no_email))
  }

  @Test
  fun `a failure ack shows the mapped error message`() {
    val client =
      FakeWatchLinkClient(
        autoAck = { id -> SignInAck.Failure(id, CompanionSignInErrorCode.UNAUTHORIZED) }
      )
    setContent(SignInViewModel(client))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_error_unauthorized))
  }

  @Test
  fun `no ack within the timeout shows the timeout message`() {
    val client = FakeWatchLinkClient(autoAck = { null })
    setContent(SignInViewModel(client, resultTimeoutMillis = SHORT_TIMEOUT_MILLIS))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_status_timeout))
  }

  @Test
  fun `while waiting for the watch, the status text shows and the send button is disabled`() {
    // Longer than WAIT_TIMEOUT_MS so the assertions below can't race a real Timeout, but still
    // short so a leaked real-time wait doesn't outlive this test by much if something goes wrong.
    val client = FakeWatchLinkClient(autoAck = { null })
    setContent(SignInViewModel(client, resultTimeoutMillis = WAIT_TIMEOUT_MS * 2))
    waitForText(string(R.string.sign_in_title))

    composeRule.onNode(hasSetTextAction()).performTextInput("key")
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).performClick()

    waitForText(string(R.string.sign_in_status_waiting))
    composeRule.onNodeWithText(string(R.string.sign_in_send_button)).assertIsNotEnabled()
  }
}
