package fi.nikosavola.clockifywear.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionPayloadsTest {
  @Test
  fun `success payload round-trips an email`() {
    val encoded = encodeSignInSuccessPayload("user@example.com")

    assertEquals("user@example.com", decodeSignInSuccessPayload(encoded))
  }

  @Test
  fun `success payload round-trips a null email as an empty payload`() {
    val encoded = encodeSignInSuccessPayload(null)

    assertEquals(0, encoded.size)
    assertNull(decodeSignInSuccessPayload(encoded))
  }

  @Test
  fun `success payload round-trips non-ASCII characters as UTF-8`() {
    val email = "jérôme@exämple.com"

    assertEquals(email, decodeSignInSuccessPayload(encodeSignInSuccessPayload(email)))
  }

  @Test
  fun `every CompanionSignInErrorCode round-trips through the failure payload codec`() {
    CompanionSignInErrorCode.entries.forEach { code ->
      val encoded = encodeSignInFailurePayload(code)

      assertEquals(code, decodeSignInFailurePayload(encoded))
    }
  }

  @Test
  fun `an unrecognized failure payload decodes to UNKNOWN`() {
    val encoded = "SOMETHING_A_NEWER_WATCH_APP_ADDED".toByteArray(Charsets.UTF_8)

    assertEquals(CompanionSignInErrorCode.UNKNOWN, decodeSignInFailurePayload(encoded))
  }

  @Test
  fun `an empty failure payload decodes to UNKNOWN`() {
    assertEquals(CompanionSignInErrorCode.UNKNOWN, decodeSignInFailurePayload(ByteArray(0)))
  }
}
