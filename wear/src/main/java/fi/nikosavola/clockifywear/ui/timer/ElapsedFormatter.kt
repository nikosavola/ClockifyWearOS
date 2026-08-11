package fi.nikosavola.clockifywear.ui.timer

import java.util.Locale

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/**
 * Formats a duration given in whole seconds as `HH:MM:SS`. A negative input clamps to zero: a clock
 * skew between the watch and the server should render as 00:00:00, never a negative timer.
 */
fun formatElapsed(totalSeconds: Long): String {
  val clamped = totalSeconds.coerceAtLeast(0)
  val hours = clamped / SECONDS_PER_HOUR
  val minutes = clamped % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
  val seconds = clamped % SECONDS_PER_MINUTE
  return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * Formats a duration given in whole seconds as `H:MM`, dropping seconds. For surfaces (Tile,
 * complication) that refresh on a cadence far coarser than a second, showing `HH:MM:SS` implies
 * live-second accuracy the surface can't actually deliver; this rounds down to whole minutes so the
 * displayed precision matches the real refresh cadence.
 */
fun formatElapsedMinutes(totalSeconds: Long): String {
  val clamped = totalSeconds.coerceAtLeast(0)
  val hours = clamped / SECONDS_PER_HOUR
  val minutes = clamped % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
  return String.format(Locale.US, "%d:%02d", hours, minutes)
}
