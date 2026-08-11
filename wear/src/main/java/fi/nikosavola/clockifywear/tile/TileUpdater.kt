package fi.nikosavola.clockifywear.tile

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import fi.nikosavola.clockifywear.complication.ClockifyComplicationDataSourceService
import fi.nikosavola.clockifywear.ui.timer.TimerUiState

private const val TAG = "TileUpdater"

/**
 * Pushes a refresh to the Tile and complication surfaces whenever the timer state changes, so both
 * reflect a start/stop without waiting out their own periodic refresh. Driven purely by
 * [TimerUiState] transitions, same trigger
 * [fi.nikosavola.clockifywear.notification.OngoingTimerNotifier] reacts to.
 */
class TileUpdater(context: Context) {
  // Defensive: guarantees this is always an Application context, never an Activity, regardless of
  // what the caller passes in. Same reasoning as OngoingTimerNotifier.
  private val context: Context = context.applicationContext

  // The state itself isn't inspected: both ClockifyTileService and
  // ClockifyComplicationDataSourceService re-fetch fresh state from the repository on every
  // request, so any transition just needs to trigger a re-render, not carry a value. The parameter
  // exists only to match the shared `(TimerUiState) -> Unit` callback signature NavGraph chains
  // this into alongside OngoingTimerNotifier.onTimerStateChanged.
  @Suppress("UnusedParameter")
  fun onTimerStateChanged(state: TimerUiState) {
    refresh()
  }

  /** Same refresh as [onTimerStateChanged], for call sites with no [TimerUiState] to hand it. */
  fun refresh() {
    requestTileUpdate()
    requestComplicationUpdate()
  }

  private fun requestTileUpdate() {
    try {
      TileService.getUpdater(context).requestUpdate(ClockifyTileService::class.java)
    } catch (e: IllegalStateException) {
      // Both system Tile/complication updates cross a Binder boundary to a real system service
      // that doesn't exist in a unit-test JVM (Robolectric) and, defensively, may not exist on
      // every real device configuration either. A push-refresh failing here should never crash
      // the app, same posture OngoingTimerNotifier takes around the notification-permission check.
      Log.w(TAG, "Tile update request failed", e)
    } catch (e: SecurityException) {
      // See above.
      Log.w(TAG, "Tile update request failed", e)
    }
  }

  private fun requestComplicationUpdate() {
    try {
      ComplicationDataSourceUpdateRequester.create(
          context,
          ComponentName(context, ClockifyComplicationDataSourceService::class.java),
        )
        .requestUpdateAll()
    } catch (e: IllegalStateException) {
      // See requestTileUpdate's comment.
      Log.w(TAG, "Complication update request failed", e)
    } catch (e: SecurityException) {
      // See requestTileUpdate's comment.
      Log.w(TAG, "Complication update request failed", e)
    }
  }
}
