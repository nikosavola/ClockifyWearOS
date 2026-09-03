package fi.nikosavola.clockifywear.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionSignInErrorCodeTest {
  @Test
  fun `fromWireValue resolves every known name`() {
    CompanionSignInErrorCode.entries.forEach { code ->
      assertEquals(code, CompanionSignInErrorCode.fromWireValue(code.name))
    }
  }

  @Test
  fun `fromWireValue falls back to UNKNOWN for an unrecognized value`() {
    assertEquals(
      CompanionSignInErrorCode.UNKNOWN,
      CompanionSignInErrorCode.fromWireValue("something_a_newer_watch_app_added"),
    )
  }
}
