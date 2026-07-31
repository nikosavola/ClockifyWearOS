package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.clockifyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectDtoTest {
  @Test
  fun `decodes known fields and tolerates unknown ones`() {
    val json =
      """
      {
        "id": "5f8a1b2c3d4e5f6a7b8c9d20",
        "name": "Website Redesign",
        "color": "#FF00FF",
        "archived": true,
        "clientName": "Acme Inc",
        "workspaceId": "5f8a1b2c3d4e5f6a7b8c9d10",
        "hourlyRate": { "amount": 5000, "currency": "USD" }
      }
      """
        .trimIndent()

    val project = clockifyJson.decodeFromString(ProjectDto.serializer(), json)

    assertEquals("5f8a1b2c3d4e5f6a7b8c9d20", project.id)
    assertEquals("Website Redesign", project.name)
    assertEquals("#FF00FF", project.color)
    assertEquals(true, project.archived)
    assertEquals("Acme Inc", project.clientName)
  }

  @Test
  fun `missing optional fields decode to defaults`() {
    val project =
      clockifyJson.decodeFromString(
        ProjectDto.serializer(),
        """{"id": "abc", "name": "No Extras"}""",
      )

    assertNull(project.color)
    assertFalse(project.archived)
    assertNull(project.clientName)
  }
}
