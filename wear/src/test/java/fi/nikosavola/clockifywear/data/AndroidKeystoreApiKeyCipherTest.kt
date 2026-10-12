package fi.nikosavola.clockifywear.data

import android.util.Base64
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The provider [AndroidKeystoreApiKeyCipher] asks for is not registered here, so a successful
 * decrypt stays device-only. What is pinned is that neither a corrupt stored record nor a keystore
 * that cannot produce the key throws: both come back null, which is what lets the app treat a wiped
 * keystore as signed out instead of crashing on a cold start.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreApiKeyCipherTest {
  private val cipher = AndroidKeystoreApiKeyCipher()

  @Test
  fun `decrypt returns null for a value that is not base64 at all`() {
    assertNull(cipher.decrypt("!!!!not-base64!!!!"))
  }

  @Test
  fun `decrypt returns null for a payload too short to hold an iv`() {
    val tooShort = Base64.encodeToString(byteArrayOf(1, 2, 3, 4), Base64.NO_WRAP)

    assertNull(cipher.decrypt(tooShort))
  }

  @Test
  fun `decrypt returns null for an empty value`() {
    assertNull(cipher.decrypt(""))
  }

  // The test above stops short of the Keystore, so on its own it would still pass if the catch
  // around the decrypt were narrowed to a single exception type that only a bad GCM tag produces.
  // This payload is long enough to reach the Keystore call itself, which is how a wiped keystore
  // fails on a real device.
  @Test
  fun `decrypt returns null when the keystore cannot serve the key`() {
    val wellFormed = Base64.encodeToString(ByteArray(WELL_FORMED_PAYLOAD_BYTES), Base64.NO_WRAP)

    assertNull(cipher.decrypt(wellFormed))
  }
}

// A 12-byte IV plus a few bytes of the ciphertext beside it: past the size guard, so the decrypt
// below reaches the Keystore rather than being rejected by the length check.
private const val WELL_FORMED_PAYLOAD_BYTES = 16
