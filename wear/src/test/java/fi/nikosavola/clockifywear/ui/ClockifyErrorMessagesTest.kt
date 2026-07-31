package fi.nikosavola.clockifywear.ui

import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// errorMessageRes/requiresSignIn are pure functions over generated R.string ints: no
// Robolectric/Compose needed to test the mapping itself.
class ClockifyErrorMessagesTest {
  @Test
  fun `each ClockifyError maps to its own string resource`() {
    assertEquals(R.string.error_unauthorized, errorMessageRes(ClockifyError.Unauthorized))
    assertEquals(R.string.error_rate_limited, errorMessageRes(ClockifyError.RateLimited))
    assertEquals(R.string.error_offline, errorMessageRes(ClockifyError.Offline))
    assertEquals(R.string.error_http, errorMessageRes(ClockifyError.Http(500)))
    assertEquals(R.string.error_parse, errorMessageRes(ClockifyError.ParseError))
    assertEquals(R.string.error_no_workspace, errorMessageRes(ClockifyError.NoWorkspaceFound))
    assertEquals(R.string.error_not_signed_in, errorMessageRes(ClockifyError.NotSignedIn))
  }

  @Test
  fun `Unauthorized and NotSignedIn require routing to Settings`() {
    assertTrue(requiresSignIn(ClockifyError.Unauthorized))
    assertTrue(requiresSignIn(ClockifyError.NotSignedIn))
  }

  @Test
  fun `other errors are retryable instead of routing to Settings`() {
    assertFalse(requiresSignIn(ClockifyError.RateLimited))
    assertFalse(requiresSignIn(ClockifyError.Offline))
    assertFalse(requiresSignIn(ClockifyError.Http(500)))
    assertFalse(requiresSignIn(ClockifyError.ParseError))
    assertFalse(requiresSignIn(ClockifyError.NoWorkspaceFound))
  }
}
