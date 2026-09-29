package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.clockifyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDtoTest {
  @Test
  fun `decodes known fields and tolerates unknown ones`() {
    val json =
      """
      {
        "id": "5f8a1b2c3d4e5f6a7b8c9d30",
        "name": "Implement login",
        "status": "ACTIVE",
        "projectId": "5f8a1b2c3d4e5f6a7b8c9d20",
        "assigneeIds": []
      }
      """
        .trimIndent()

    val task = clockifyJson.decodeFromString(TaskDto.serializer(), json)

    assertEquals("5f8a1b2c3d4e5f6a7b8c9d30", task.id)
    assertEquals("Implement login", task.name)
    assertEquals("ACTIVE", task.status)
  }

  @Test
  fun `missing status decodes to null`() {
    val task = clockifyJson.decodeFromString(TaskDto.serializer(), """{"id": "abc", "name": "x"}""")

    assertNull(task.status)
  }
}
