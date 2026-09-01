package fi.nikosavola.clockifywear.ui.summary

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SummaryBoundariesTest {
  private lateinit var originalLocale: Locale

  @Before
  fun setUp() {
    originalLocale = Locale.getDefault()
  }

  @After
  fun tearDown() {
    Locale.setDefault(originalLocale)
  }

  @Test
  fun `boundaries land on the locale's first day of week, at UTC midnight`() {
    Locale.setDefault(Locale.Builder().setLanguage("fi").setRegion("FI").build()) // Monday-first
    val now = Instant.parse("2026-08-05T14:30:00Z") // a Wednesday

    val boundaries = summaryBoundaries(now, ZoneOffset.UTC)

    assertEquals(Instant.parse("2026-08-05T00:00:00Z"), boundaries.todayStart)
    assertEquals(Instant.parse("2026-08-03T00:00:00Z"), boundaries.thisWeekStart) // Monday
    assertEquals(Instant.parse("2026-07-27T00:00:00Z"), boundaries.lastWeekStart) // previous Monday
  }

  @Test
  fun `this-week boundary follows the default locale's first day of week`() {
    Locale.setDefault(Locale.US) // Sunday-first
    val now = Instant.parse("2026-08-05T14:30:00Z") // a Wednesday

    val boundaries = summaryBoundaries(now, ZoneOffset.UTC)

    assertEquals(Instant.parse("2026-08-02T00:00:00Z"), boundaries.thisWeekStart) // Sunday
  }

  @Test
  fun `boundaries are computed in the given zone, not UTC`() {
    Locale.setDefault(Locale.Builder().setLanguage("fi").setRegion("FI").build())
    // 21:30 UTC is already 00:30 the next day in Helsinki's summer (UTC+3) offset.
    val now = Instant.parse("2026-08-05T21:30:00Z")

    val boundaries = summaryBoundaries(now, ZoneId.of("Europe/Helsinki"))

    assertEquals(Instant.parse("2026-08-05T21:00:00Z"), boundaries.todayStart)
  }
}
