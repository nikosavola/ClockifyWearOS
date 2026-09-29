package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.clockifyJson
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class StartTimeEntryRequestTest {
  @Test
  fun `minimal request omits null fields instead of sending them`() {
    val request = StartTimeEntryRequest(start = Instant.parse("2026-07-31T09:00:00Z"))

    val json = clockifyJson.encodeToString(StartTimeEntryRequest.serializer(), request)

    assertEquals("""{"start":"2026-07-31T09:00:00Z"}""", json)
  }

  @Test
  fun `full request serializes every field`() {
    val request =
      StartTimeEntryRequest(
        start = Instant.parse("2026-07-31T09:00:00Z"),
        projectId = "5f8a1b2c3d4e5f6a7b8c9d20",
        taskId = "5f8a1b2c3d4e5f6a7b8c9d30",
        description = "Fixing bugs",
        billable = true,
      )

    val json = clockifyJson.encodeToString(StartTimeEntryRequest.serializer(), request)

    assertEquals(
      """{"start":"2026-07-31T09:00:00Z","projectId":"5f8a1b2c3d4e5f6a7b8c9d20",""" +
        """"taskId":"5f8a1b2c3d4e5f6a7b8c9d30","description":"Fixing bugs","billable":true}""",
      json,
    )
  }
}
