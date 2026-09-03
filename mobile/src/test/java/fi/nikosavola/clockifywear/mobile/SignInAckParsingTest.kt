package fi.nikosavola.clockifywear.mobile

import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode
import fi.nikosavola.clockifywear.companion.apiKeyRequestPath
import fi.nikosavola.clockifywear.companion.newRequestId
import fi.nikosavola.clockifywear.companion.signInFailurePath
import fi.nikosavola.clockifywear.companion.signInSuccessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Plain JVM unit test: parseSignInAck is a pure function, no Play Services or Android dependency
// needed to exercise it.
class SignInAckParsingTest {
  private val requestId = newRequestId()

  @Test
  fun `a success path with an email parses to Success`() {
    val ack = parseSignInAck(signInSuccessPath(requestId), "user@example.com".toByteArray())

    assertEquals(SignInAck.Success(requestId, "user@example.com"), ack)
  }

  @Test
  fun `a success path with an empty payload parses to Success with a null email`() {
    val ack = parseSignInAck(signInSuccessPath(requestId), ByteArray(0))

    assertEquals(SignInAck.Success(requestId, null), ack)
  }

  @Test
  fun `a failure path with a known error code parses to Failure`() {
    val ack = parseSignInAck(signInFailurePath(requestId), "UNAUTHORIZED".toByteArray())

    assertEquals(SignInAck.Failure(requestId, CompanionSignInErrorCode.UNAUTHORIZED), ack)
  }

  @Test
  fun `a failure path with an unrecognized error code parses to UNKNOWN`() {
    val ack = parseSignInAck(signInFailurePath(requestId), "WAT".toByteArray())

    assertEquals(SignInAck.Failure(requestId, CompanionSignInErrorCode.UNKNOWN), ack)
  }

  @Test
  fun `the phone's own outgoing request path is never mistaken for a reply`() {
    // MessageClient's listener sees every message on /clockify, including ones this app itself
    // sent - it must not treat its own outgoing request as an incoming ack.
    val ack = parseSignInAck(apiKeyRequestPath(requestId), ByteArray(0))

    assertNull(ack)
  }

  @Test
  fun `unrelated or malformed paths parse to null`() {
    listOf("/clockify/", "/other/sign-in-success/x", "").forEach { path ->
      assertNull(parseSignInAck(path, ByteArray(0)))
    }
  }

  @Test
  fun `an empty request id still parses, distinct from any real generated id`() {
    // A real newRequestId() is a non-empty UUID, so SignInViewModel's requestId filter can never
    // match this - documented here rather than left as an implicit assumption.
    val ack = parseSignInAck(signInSuccessPath(""), "user@example.com".toByteArray())

    assertEquals(SignInAck.Success("", "user@example.com"), ack)
  }
}
