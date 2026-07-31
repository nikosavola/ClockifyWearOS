package fi.nikosavola.clockifywear.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserDtoTest {
  // Trimmed from a real GET /user response; settings/status are fields we don't model yet, which
  // is exactly what ignoreUnknownKeys must tolerate.
  private val json =
    """
    {
      "id": "5f8a1b2c3d4e5f6a7b8c9d0e",
      "email": "someone@example.com",
      "name": "Someone",
      "activeWorkspace": "5f8a1b2c3d4e5f6a7b8c9d10",
      "defaultWorkspace": "5f8a1b2c3d4e5f6a7b8c9d10",
      "status": "ACTIVE",
      "settings": { "timeZone": "Europe/Helsinki" }
    }
    """
      .trimIndent()

  @Test
  fun `decodes known fields and tolerates unknown ones`() {
    // Decoding through the generated serializer accessor, not decodeFromString<UserDto>, so a
    // missing @Serializable plugin application fails the build at compile time (unresolved
    // reference) instead of at test runtime.
    val decoder = Json { ignoreUnknownKeys = true }
    val user = decoder.decodeFromString(UserDto.serializer(), json)

    assertEquals("5f8a1b2c3d4e5f6a7b8c9d0e", user.id)
    assertEquals("someone@example.com", user.email)
    assertEquals("Someone", user.name)
    assertEquals("5f8a1b2c3d4e5f6a7b8c9d10", user.activeWorkspace)
    assertEquals("5f8a1b2c3d4e5f6a7b8c9d10", user.defaultWorkspace)
  }

  @Test
  fun `missing optional fields decode to null`() {
    val decoder = Json { ignoreUnknownKeys = true }
    val user = decoder.decodeFromString(UserDto.serializer(), """{"id": "abc"}""")

    assertEquals("abc", user.id)
    assertNull(user.email)
    assertNull(user.name)
    assertNull(user.activeWorkspace)
    assertNull(user.defaultWorkspace)
  }
}
