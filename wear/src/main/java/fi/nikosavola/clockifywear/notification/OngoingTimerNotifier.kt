package fi.nikosavola.clockifywear.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.MainActivity
import fi.nikosavola.clockifywear.ui.timer.TimerUiState
import java.time.Instant

private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "ongoing_timer"

/**
 * Posts the always-on "timer running" notification (WO-V4) via
 * [androidx.wear.ongoing.OngoingActivity] so the running timer surfaces on the watch face. Driven
 * purely by [TimerUiState] transitions, not by the elapsed-seconds ticker: the system renders and
 * ticks the stopwatch text itself from [Status.StopwatchPart]'s start time.
 */
class OngoingTimerNotifier(private val context: Context) {
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

    val touchIntent =
      PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentTitle(
          projectName ?: context.getString(R.string.notification_timer_running_title)
        )
        .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        .setContentIntent(touchIntent)
        .setSilent(true)

    val status = Status.forPart(Status.StopwatchPart(startInstant.toEpochMilli()))

    OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
      .setStaticIcon(R.drawable.ic_launcher_foreground)
      .setTouchIntent(touchIntent)
      .setStatus(status)
      .build()
      .apply(context)

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
  }

  private fun cancel() {
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
}
