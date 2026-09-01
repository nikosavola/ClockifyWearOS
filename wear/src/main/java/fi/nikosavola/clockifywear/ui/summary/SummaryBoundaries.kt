package fi.nikosavola.clockifywear.ui.summary

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** The three bucket boundaries the Summary screen groups entries into, all as UTC instants. */
data class SummaryBoundaries(
  val lastWeekStart: Instant,
  val thisWeekStart: Instant,
  val todayStart: Instant,
)

/**
 * Computes day/week boundaries in [zoneId] as of [now]. The week's first day follows
 * [Locale.getDefault] (e.g. Monday in Finland, Sunday in the US) rather than assuming ISO's Monday,
 * since Clockify itself has no per-account week-start setting this could instead defer to.
 */
fun summaryBoundaries(now: Instant, zoneId: ZoneId): SummaryBoundaries {
  val today = now.atZone(zoneId).toLocalDate()
  val todayStart = today.atStartOfDay(zoneId).toInstant()
  val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
  val thisWeekStartDate = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
  val thisWeekStart = thisWeekStartDate.atStartOfDay(zoneId).toInstant()
  val lastWeekStart = thisWeekStartDate.minusWeeks(1).atStartOfDay(zoneId).toInstant()
  return SummaryBoundaries(lastWeekStart, thisWeekStart, todayStart)
}
