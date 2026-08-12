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

  @Test
  fun `formatElapsedHoursMinutesUnits shows minutes only under an hour`() {
    assertEquals("0min", formatElapsedHoursMinutesUnits(0))
    assertEquals("0min", formatElapsedHoursMinutesUnits(59))
    assertEquals("1min", formatElapsedHoursMinutesUnits(65))
    assertEquals("59min", formatElapsedHoursMinutesUnits(3_599))
  }

  @Test
  fun `formatElapsedHoursMinutesUnits omits minutes when exactly zero`() {
    assertEquals("1h", formatElapsedHoursMinutesUnits(3_600))
    assertEquals("100h", formatElapsedHoursMinutesUnits(360_000))
  }

  @Test
  fun `formatElapsedHoursMinutesUnits shows both units when both are non-zero`() {
    assertEquals("1h 1min", formatElapsedHoursMinutesUnits(3_660))
    assertEquals("2h 15min", formatElapsedHoursMinutesUnits(8_130))
  }

  @Test
  fun `formatElapsedHoursMinutesUnits clamps negative input to zero`() {
    assertEquals("0min", formatElapsedHoursMinutesUnits(-42))
  }
}
