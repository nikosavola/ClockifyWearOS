package fi.nikosavola.clockifywear.ui.timer

import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JVM unit test: formatElapsed is a pure function, no Robolectric/Android needed.
class ElapsedFormatterTest {
  @Test
  fun `zero seconds formats as 00-00-00`() {
    assertEquals("00:00:00", formatElapsed(0))
  }

  @Test
  fun `seconds and minutes format with leading zeros`() {
    assertEquals("00:01:05", formatElapsed(65))
  }

  @Test
  fun `durations at or over one hour carry into the hours field`() {
    assertEquals("01:00:00", formatElapsed(3_600))
    assertEquals("02:15:30", formatElapsed(8_130))
  }

  @Test
  fun `durations past 99 hours still render, unpadded past two digits`() {
    assertEquals("100:00:00", formatElapsed(360_000))
  }

  @Test
  fun `negative input clamps to zero instead of rendering a negative timer`() {
    assertEquals("00:00:00", formatElapsed(-42))
  }
}
