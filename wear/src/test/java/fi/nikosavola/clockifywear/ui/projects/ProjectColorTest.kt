package fi.nikosavola.clockifywear.ui.projects

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectColorTest {
  @Test
  fun `parses a well-formed hex color`() {
    val color = parseProjectColor("#1976D2")

    assertEquals(Color(red = 0x19, green = 0x76, blue = 0xD2), color)
  }

  @Test
  fun `null input returns null`() {
    assertNull(parseProjectColor(null))
  }

  @Test
  fun `missing hash prefix returns null`() {
    assertNull(parseProjectColor("1976D2"))
  }

  @Test
  fun `wrong length returns null`() {
    assertNull(parseProjectColor("#1976D"))
  }

  @Test
  fun `non-hex characters return null`() {
    assertNull(parseProjectColor("#GGGGGG"))
  }
}
