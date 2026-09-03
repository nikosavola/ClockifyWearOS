package fi.nikosavola.clockifywear.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionProtocolTest {
  @Test
  fun `request id round-trips through each path builder and parser`() {
    val requestId = newRequestId()

    assertEquals(requestId, requestIdFromApiKeyRequestPath(apiKeyRequestPath(requestId)))
    assertEquals(requestId, requestIdFromSignInSuccessPath(signInSuccessPath(requestId)))
    assertEquals(requestId, requestIdFromSignInFailurePath(signInFailurePath(requestId)))
  }

  @Test
  fun `parsers reject paths from a different message kind`() {
    val requestId = newRequestId()

    assertNull(requestIdFromSignInSuccessPath(apiKeyRequestPath(requestId)))
    assertNull(requestIdFromSignInFailurePath(signInSuccessPath(requestId)))
    assertNull(requestIdFromApiKeyRequestPath(signInFailurePath(requestId)))
  }

  @Test
  fun `newRequestId returns distinct values`() {
    assertEquals(false, newRequestId() == newRequestId())
  }
}
