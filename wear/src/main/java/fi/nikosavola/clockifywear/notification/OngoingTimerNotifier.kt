package fi.nikosavola.clockifywear.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.MainActivity
import fi.nikosavola.clockifywear.ui.timer.TimerUiState
import java.time.Duration
import java.time.Instant

private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "ongoing_timer"
private const val DISMISS_REQUEST_CODE = 2

/**
 * Posts the always-on "timer running" notification (WO-V4) so the running timer surfaces on the
 * watch face. Driven purely by [TimerUiState] transitions, not by the elapsed-seconds ticker: both
 * implementations below hand the system a start time and let it render and tick the elapsed time
 * itself.
 *
 * Wear OS 7 replaced the Ongoing Activities API with Live Updates, and the two do not coexist on
 * one device, so the path splits on [Build.VERSION_CODES.BAKLAVA]: Live Updates (a promoted ongoing
 * notification) at or above it, `androidx.wear.ongoing` below, where Live Updates do not exist at
 * all.
 */
class OngoingTimerNotifier(
  context: Context,
  // Injected so tests can pin the stopwatch origin instead of racing the wall clock.
  private val nowInstant: () -> Instant = Instant::now,
) {
  // Defensive: guarantees this is always an Application context, never an Activity, regardless of
  // what the caller passes in.
  private val context: Context = context.applicationContext

  fun onTimerStateChanged(state: TimerUiState) {
    if (state is TimerUiState.Running) {
      start(startInstant = state.startInstant, projectName = state.projectName)
    } else {
      cancel()
    }
  }

  private fun start(startInstant: Instant, projectName: String?) {
    createChannel()
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    val startMillis = startInstant.toEpochMilli()

    // Live Updates only: re-posting after the user swiped the card away would resurrect something
    // they just dismissed. Android's guidance is explicit that a dismissed Live Update stays
    // dismissed, so only a genuinely different entry gets to post again.
    if (isLiveUpdateSupported && DismissedLiveUpdate.isDismissed(context, startMillis)) {
      return
    }

    val touchIntent =
      PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_clockify)
        .setOngoing(true)
        .setContentTitle(
          projectName ?: context.getString(R.string.notification_timer_running_title)
        )
        .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        .setContentIntent(touchIntent)
        .setSilent(true)

    if (isLiveUpdateSupported) {
      builder
        .setRequestPromotedOngoing(true)
        .setUsesChronometer(true)
        // Epoch millis here, unlike Status.StopwatchPart's elapsedRealtime() domain below: the
        // system ticks the chronometer from an absolute wall-clock time.
        .setWhen(startMillis)
        .setDeleteIntent(liveUpdateDismissedIntent(startMillis))
    } else {
      // Below Wear 7 there are no Live Updates at all, so the Ongoing Activities API is what
      // surfaces the running timer on the watch face.
      builder.applyOngoingActivity(touchIntent, startInstant)
    }

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
  }

  private fun NotificationCompat.Builder.applyOngoingActivity(
    touchIntent: PendingIntent,
    startInstant: Instant,
  ) {
    val timeZeroMillis =
      stopwatchTimeZeroMillis(startInstant, nowInstant(), SystemClock.elapsedRealtime())
    val status = Status.forPart(Status.StopwatchPart(timeZeroMillis))

    OngoingActivity.Builder(context, NOTIFICATION_ID, this)
      .setStaticIcon(R.drawable.ic_stat_clockify)
      .setTouchIntent(touchIntent)
      .setStatus(status)
      .build()
      .apply(context)
  }

  private fun liveUpdateDismissedIntent(startMillis: Long): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      DISMISS_REQUEST_CODE,
      Intent(context, LiveUpdateDismissedReceiver::class.java)
        .putExtra(LiveUpdateDismissedReceiver.EXTRA_START_MILLIS, startMillis),
      // FLAG_UPDATE_CURRENT is load-bearing, not tidiness: a PendingIntent's identity is its
      // request code plus Intent.filterEquals, and extras are not part of that comparison. Without
      // it, the second timer to run in a process would reuse the first timer's PendingIntent and
      // with it the first timer's start time, so dismissing the second would record a dismissal of
      // the first and leave the second free to be re-posted.
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  fun cancel() {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }

  // Safe to call every time start() runs: NotificationManagerCompat no-ops if the channel is
  // unchanged, and this avoids needing a separate one-time init hook.
  private fun createChannel() {
    val channel =
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
        .setName(context.getString(R.string.notification_timer_running_title))
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
  }

  private companion object {
    val isLiveUpdateSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
  }
}

/**
 * When the system's stopwatch should treat the entry as having started, in the
 * [SystemClock.elapsedRealtime] domain it ticks in.
 *
 * `StopwatchPart`'s Long is an `elapsedRealtime()` timestamp, not an epoch millis one -
 * undocumented in the decompiled signature (both are just "long"), confirmed only by checking real
 * usage of this API. Passing `startInstant.toEpochMilli()` directly rendered as a wildly negative
 * elapsed time on-device: `elapsedRealtime()` is boot-relative and far smaller than a Unix epoch
 * value, so "elapsedRealtime() - epochMillis" underflowed hugely negative. Converting the
 * wall-clock elapsed duration into the elapsedRealtime domain fixes it.
 */
internal fun stopwatchTimeZeroMillis(
  startInstant: Instant,
  nowInstant: Instant,
  elapsedRealtimeMillis: Long,
): Long = elapsedRealtimeMillis - Duration.between(startInstant, nowInstant).toMillis()
