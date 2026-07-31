package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.clockifyJson
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceDtoTest {
  @Test
  fun `decodes known fields and tolerates unknown ones`() {
    val json =
      """
      {
        "id": "5f8a1b2c3d4e5f6a7b8c9d10",
        "name": "My Workspace",
        "memberships": []
      }
      """
        .trimIndent()

    val workspace = clockifyJson.decodeFromString(WorkspaceDto.serializer(), json)

    assertEquals("5f8a1b2c3d4e5f6a7b8c9d10", workspace.id)
    assertEquals("My Workspace", workspace.name)
  }
}
