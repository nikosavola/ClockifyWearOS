package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.clockifyJson
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeEntryDtoTest {
  @Test
  fun `decodes a running entry with no end and tolerates unknown fields`() {
    val json =
      """
      {
        "id": "5f8a1b2c3d4e5f6a7b8c9d40",
        "description": "Fixing bugs",
        "projectId": "5f8a1b2c3d4e5f6a7b8c9d20",
        "taskId": "5f8a1b2c3d4e5f6a7b8c9d30",
        "billable": true,
        "timeInterval": { "start": "2026-07-31T09:00:00.500Z", "end": null },
        "userId": "5f8a1b2c3d4e5f6a7b8c9d0e",
        "tagIds": []
      }
      """
        .trimIndent()

    val entry = clockifyJson.decodeFromString(TimeEntryDto.serializer(), json)

    assertEquals("5f8a1b2c3d4e5f6a7b8c9d40", entry.id)
    assertEquals("Fixing bugs", entry.description)
    assertEquals("5f8a1b2c3d4e5f6a7b8c9d20", entry.projectId)
    assertEquals("5f8a1b2c3d4e5f6a7b8c9d30", entry.taskId)
    assertEquals(true, entry.billable)
    assertEquals(Instant.parse("2026-07-31T09:00:00.500Z"), entry.timeInterval.start)
    assertNull(entry.timeInterval.end)
  }

  @Test
  fun `decodes a stopped entry with an end`() {
    val json =
      """
      {
        "id": "5f8a1b2c3d4e5f6a7b8c9d41",
        "timeInterval": { "start": "2026-07-31T09:00:00Z", "end": "2026-07-31T10:00:00Z" }
      }
      """
        .trimIndent()

    val entry = clockifyJson.decodeFromString(TimeEntryDto.serializer(), json)

    assertNull(entry.description)
    assertEquals(false, entry.billable)
    assertEquals(Instant.parse("2026-07-31T10:00:00Z"), entry.timeInterval.end)
  }
}
