package fi.nikosavola.clockifywear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeApiKeyCipherTest {
  private val cipher = FakeApiKeyCipher()

  @Test
  fun `decrypt reverses encrypt`() {
    val encrypted = cipher.encrypt("a-secret-value")

    assertNotEquals("a-secret-value", encrypted)
    assertEquals("a-secret-value", cipher.decrypt(encrypted))
  }

  @Test
  fun `decrypt returns null for input it did not encrypt`() {
    assertNull(cipher.decrypt("plain-value-never-passed-through-encrypt"))
  }
}
