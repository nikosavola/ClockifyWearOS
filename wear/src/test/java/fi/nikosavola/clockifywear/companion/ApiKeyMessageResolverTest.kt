package fi.nikosavola.clockifywear.companion

import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.UserDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JVM unit test: ApiKeyMessageResolver takes plain suspend lambdas, no Android/Robolectric
// dependency needed - mirrors TileClickResolverTest.
class ApiKeyMessageResolverTest {
  private val resolver = ApiKeyMessageResolver()
  private val notSignedIn: suspend () -> Boolean = { false }

  @Test
  fun `successful sign-in reports the account email`() = runTest {
    var receivedApiKey: String? = null
    val signIn: suspend (String) -> ClockifyResult<UserDto> = { apiKey ->
      receivedApiKey = apiKey
      ClockifyResult.Success(UserDto(id = "u1", email = "user@example.com"))
    }

    val outcome = resolver.resolve("secret-key", notSignedIn, signIn)

    assertEquals("secret-key", receivedApiKey)
    assertEquals(CompanionSignInOutcome.Success("user@example.com"), outcome)
  }

  @Test
  fun `successful sign-in with no email still reports success`() = runTest {
    val outcome =
      resolver.resolve("secret-key", notSignedIn) {
        ClockifyResult.Success(UserDto(id = "u1", email = null))
      }

    assertEquals(CompanionSignInOutcome.Success(null), outcome)
  }

  @Test
  fun `rejected key maps to the unauthorized error code`() = runTest {
    val outcome =
      resolver.resolve("bad-key", notSignedIn) {
        ClockifyResult.Failure(ClockifyError.Unauthorized)
      }

    assertEquals(CompanionSignInOutcome.Failure(CompanionSignInErrorCode.UNAUTHORIZED), outcome)
  }

  @Test
  fun `offline failure maps to the offline error code`() = runTest {
    val outcome =
      resolver.resolve("key", notSignedIn) { ClockifyResult.Failure(ClockifyError.Offline) }

    assertEquals(CompanionSignInOutcome.Failure(CompanionSignInErrorCode.OFFLINE), outcome)
  }

  @Test
  fun `an already signed-in watch refuses the request without calling signIn`() = runTest {
    var signInCalled = false
    val outcome =
      resolver.resolve("key", isAlreadySignedIn = { true }) {
        signInCalled = true
        ClockifyResult.Success(UserDto(id = "u1"))
      }

    assertFalse(signInCalled)
    assertEquals(
      CompanionSignInOutcome.Failure(CompanionSignInErrorCode.ALREADY_SIGNED_IN),
      outcome,
    )
  }

  @Test
  fun `every ClockifyError maps to a distinct error code`() {
    val errors =
      listOf(
        ClockifyError.Unauthorized,
        ClockifyError.RateLimited,
        ClockifyError.Offline,
        ClockifyError.Http(500),
        ClockifyError.ParseError,
        ClockifyError.NoWorkspaceFound,
        ClockifyError.NotSignedIn,
      )

    val codes = errors.map { it.toCompanionErrorCode() }

    assertEquals(codes.distinct(), codes)
  }

  @Test
  fun `toReplyPath and toReplyPayload round-trip through the request id and outcome`() {
    val requestId = newRequestId()

    val success = CompanionSignInOutcome.Success("user@example.com")
    assertEquals(signInSuccessPath(requestId), success.toReplyPath(requestId))
    assertEquals("user@example.com", String(success.toReplyPayload(), Charsets.UTF_8))

    val failure = CompanionSignInOutcome.Failure(CompanionSignInErrorCode.OFFLINE)
    assertEquals(signInFailurePath(requestId), failure.toReplyPath(requestId))
    assertEquals("OFFLINE", String(failure.toReplyPayload(), Charsets.UTF_8))
  }

  @Test
  fun `a successful outcome with no email replies with an empty payload`() {
    val payload = CompanionSignInOutcome.Success(null).toReplyPayload()

    assertTrue(payload.isEmpty())
  }
}
