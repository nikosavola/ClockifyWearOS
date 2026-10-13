package fi.nikosavola.clockifywear.notification

import android.content.Context

/**
 * The start time (epoch millis) of the Live Update the user swiped away, so that entry is not
 * re-posted after being dismissed.
 *
 * Persisted rather than held in memory on purpose: Wear reclaims app processes aggressively, and a
 * dismissal that only lived in memory would be forgotten on the next cold start, bringing back a
 * card the user had already swiped away - which is exactly what Android's Live Updates guidance
 * forbids.
 *
 * SharedPreferences rather than the DataStore the app's settings use: the delete intent is handled
 * in a [android.content.BroadcastReceiver], whose `onReceive` has to return before the process may
 * be reclaimed, so this write has to be synchronous, and an asynchronous DataStore write could be
 * lost. It is one fire-and-forget long, not the structured settings DataStore exists for.
 *
 * Process-level rather than notifier state for a second reason too: the receiver is instantiated by
 * the system and has no way to reach the notifier instance that posted the notification.
 */
internal object DismissedLiveUpdate {
  private const val PREFS_NAME = "dismissed_live_update"
  private const val KEY_START_MILLIS = "start_millis"

  // A start time is always positive, so this can never collide with a real one.
  private const val NO_ENTRY = -1L

  fun dismiss(context: Context, startMillis: Long) {
    prefs(context).edit().putLong(KEY_START_MILLIS, startMillis).apply()
  }

  /** Dismissal is keyed to one entry, so a later timer with a different start time still posts. */
  fun isDismissed(context: Context, startMillis: Long): Boolean =
    prefs(context).getLong(KEY_START_MILLIS, NO_ENTRY) == startMillis

  /** Outlives a single test, so tests reset it rather than relying on distinct start times. */
  fun reset(context: Context) {
    prefs(context).edit().clear().apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
