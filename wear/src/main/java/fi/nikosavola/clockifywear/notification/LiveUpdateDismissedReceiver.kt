package fi.nikosavola.clockifywear.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the Live Update's delete intent, fired when the user swipes the card away. Records the
 * dismissed entry in [DismissedLiveUpdate]; see there for why this is a receiver rather than
 * notifier state.
 */
class LiveUpdateDismissedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val startMillis = intent.getLongExtra(EXTRA_START_MILLIS, MISSING_START_MILLIS)
    if (startMillis != MISSING_START_MILLIS) {
      DismissedLiveUpdate.dismiss(context, startMillis)
    }
  }

  companion object {
    const val EXTRA_START_MILLIS = "fi.nikosavola.clockifywear.extra.START_MILLIS"

    // A start time is always positive, so this can never collide with a real one.
    private const val MISSING_START_MILLIS = Long.MIN_VALUE
  }
}
