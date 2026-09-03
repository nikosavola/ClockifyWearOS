package fi.nikosavola.clockifywear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// NOT_SIGNED_IN and UNKNOWN deliberately share one generic message: NOT_SIGNED_IN can't actually
// happen from a companion sign-in attempt (ClockifyRepository.signIn never returns that error),
// and a wire code this build doesn't recognize is exactly what UNKNOWN is for - both get the same
// "something went wrong" treatment on purpose, not by oversight.
private val EXPECTED_COLLISIONS =
  setOf(CompanionSignInErrorCode.NOT_SIGNED_IN, CompanionSignInErrorCode.UNKNOWN)

@RunWith(RobolectricTestRunner::class)
class SignInErrorMessagesTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `every error code resolves to a non-blank string`() {
    CompanionSignInErrorCode.entries.forEach { code ->
      val message = context.getString(errorMessageRes(code))

      assertTrue("$code resolved to a blank message", message.isNotBlank())
    }
  }

  @Test
  fun `no error message contains an unfilled format specifier`() {
    // StatusMessage calls stringResource(errorMessageRes(...)) with no format arguments - a
    // message with a %1$s etc would throw at runtime instead of just looking wrong.
    CompanionSignInErrorCode.entries.forEach { code ->
      val message = context.getString(errorMessageRes(code))

      assertFalse("$code's message contains a format specifier: $message", message.contains("%"))
    }
  }

  @Test
  fun `the mapping is injective except the documented NOT_SIGNED_IN-UNKNOWN collision`() {
    val messagesByCode = CompanionSignInErrorCode.entries.associateWith { errorMessageRes(it) }

    val duplicates =
      messagesByCode.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }

    assertTrue(
      "unexpected duplicate message groups: $duplicates",
      duplicates.all { it.toSet() == EXPECTED_COLLISIONS },
    )
  }
}
