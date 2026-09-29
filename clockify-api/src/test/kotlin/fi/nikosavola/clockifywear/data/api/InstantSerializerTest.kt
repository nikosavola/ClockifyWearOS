package fi.nikosavola.clockifywear.data.api

import java.time.Instant
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

class InstantSerializerTest {
  @Serializable
  private data class Wrapper(@Serializable(with = InstantSerializer::class) val instant: Instant)

  @Test
  fun `serializes to whole seconds with Z suffix`() {
    val wrapper = Wrapper(Instant.parse("2026-07-31T09:00:00.123456Z"))

    val json = clockifyJson.encodeToString(Wrapper.serializer(), wrapper)

    assertEquals("""{"instant":"2026-07-31T09:00:00Z"}""", json)
  }

  @Test
  fun `deserializes fractional seconds`() {
    val wrapper =
      clockifyJson.decodeFromString(
        Wrapper.serializer(),
        """{"instant":"2026-07-31T09:00:00.987Z"}""",
      )

    assertEquals(Instant.parse("2026-07-31T09:00:00.987Z"), wrapper.instant)
  }

  @Test
  fun `round trips whole seconds`() {
    val original = Wrapper(Instant.parse("2026-01-01T00:00:00Z"))

    val json = clockifyJson.encodeToString(Wrapper.serializer(), original)
    val decoded = clockifyJson.decodeFromString(Wrapper.serializer(), json)

    assertEquals(original, decoded)
  }
}
