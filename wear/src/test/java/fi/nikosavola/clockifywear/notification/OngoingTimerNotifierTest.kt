package fi.nikosavola.clockifywear.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.timer.TimerUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"

// Pinned instead of left to targetSdk: the notification takes a different path either side of
// Wear 7 (API 36), and a silent targetSdk bump should not quietly move every test to the other one.
@Config(sdk = [36])
@RunWith(RobolectricTestRunner::class)
class OngoingTimerNotifierTest {
  private lateinit var context: Context
  private lateinit var notifier: OngoingTimerNotifier

  private val fixedNow = Instant.parse("2026-07-31T09:05:00Z")

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // API 33+ requires POST_NOTIFICATIONS to actually post; Robolectric doesn't grant it just
    // because the manifest declares it, so it must be granted explicitly here.
    shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    // Persisted, so it outlives a single test method.
    DismissedLiveUpdate.reset(context)
    notifier = OngoingTimerNotifier(context) { fixedNow }
  }

  private fun runningState(
    startInstant: Instant = Instant.parse("2026-07-31T09:00:00Z"),
    projectName: String? = "Website",
  ) =
    TimerUiState.Running(
      projectId = PROJECT_ID,
      projectName = projectName,
      projectColor = null,
      startInstant = startInstant,
      elapsedSeconds = 0,
      description = null,
    )

  private fun activeNotifications() = NotificationManagerCompat.from(context).activeNotifications

  private fun activeNotification(): Notification = activeNotifications().single().notification

  private fun activeNotificationTitle(): String? {
    val notification = activeNotification()
    return notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
  }

  // What the system does when the user swipes the card away: send the notification's own delete
  // intent, then drop the notification. Delivered directly rather than through
  // PendingIntent.send(), which Robolectric does not route to a manifest-declared receiver; it is
  // still the intent the production code built, extras and all.
  private fun dismissThroughDeleteIntent() {
    val deleteIntent = shadowOf(requireNotNull(activeNotification().deleteIntent)).savedIntent
    LiveUpdateDismissedReceiver().onReceive(context, deleteIntent)
    notifier.cancel()
  }

  private fun deleteExtraFor(startInstant: Instant): Long {
    notifier.onTimerStateChanged(runningState(startInstant = startInstant))
    val deleteIntent = requireNotNull(activeNotification().deleteIntent) { "no delete intent" }
    return shadowOf(deleteIntent)
      .savedIntent
      .getLongExtra(LiveUpdateDismissedReceiver.EXTRA_START_MILLIS, -1L)
  }

  @Test
  fun `Running state posts an active notification`() {
    notifier.onTimerStateChanged(runningState())

    assertEquals(1, activeNotifications().size)
  }

  @Test
  fun `Idle state after Running cancels the notification`() {
    notifier.onTimerStateChanged(runningState())
    assertEquals(1, activeNotifications().size)

    notifier.onTimerStateChanged(TimerUiState.Idle(hasDefaultProject = false))

    assertTrue(activeNotifications().isEmpty())
  }

  @Test
  fun `Running state with a project name shows it as the notification title`() {
    notifier.onTimerStateChanged(runningState(projectName = "Website"))

    assertEquals("Website", activeNotificationTitle())
  }

  @Test
  fun `Running state with no project name falls back to the generic title`() {
    notifier.onTimerStateChanged(runningState(projectName = null))

    assertEquals(
      context.getString(R.string.notification_timer_running_title),
      activeNotificationTitle(),
    )
  }

  @Test
  fun `two consecutive Running updates leave exactly one active notification`() {
    val start = Instant.parse("2026-07-31T09:00:00Z")

    notifier.onTimerStateChanged(runningState(startInstant = start))
    notifier.onTimerStateChanged(runningState(startInstant = start.plusSeconds(60)))

    assertEquals(1, activeNotifications().size)
  }

  @Test
  fun `Running state without POST_NOTIFICATIONS granted does not throw and posts nothing`() {
    shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    notifier.onTimerStateChanged(runningState())

    assertTrue(activeNotifications().isEmpty())
  }

  @Test
  fun `Running state requests promotion so Wear 7 shows a Live Update`() {
    notifier.onTimerStateChanged(runningState())

    assertTrue(NotificationCompat.isRequestPromotedOngoing(activeNotification()))
  }

  @Test
  fun `Running state anchors the chronometer to the entry's start so the system ticks it`() {
    val start = Instant.parse("2026-07-31T09:00:00Z")

    notifier.onTimerStateChanged(runningState(startInstant = start))

    val notification = activeNotification()
    assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    assertEquals(start.toEpochMilli(), notification.`when`)
  }

  @Test
  fun `a dismissed entry is not reposted`() {
    val start = Instant.parse("2026-07-31T09:00:00Z")
    notifier.onTimerStateChanged(runningState(startInstant = start))
    assertEquals(1, activeNotifications().size)

    dismissThroughDeleteIntent()

    notifier.onTimerStateChanged(runningState(startInstant = start))

    assertTrue(activeNotifications().isEmpty())
  }

  @Test
  fun `a later entry still posts after an earlier one was dismissed`() {
    val dismissed = Instant.parse("2026-07-31T09:00:00Z")
    DismissedLiveUpdate.dismiss(context, dismissed.toEpochMilli())

    notifier.onTimerStateChanged(runningState(startInstant = dismissed.plusSeconds(120)))

    assertEquals(1, activeNotifications().size)
  }

  @Test
  fun `each entry's delete intent carries that entry's own start time`() {
    val first = Instant.parse("2026-07-31T09:00:00Z")
    val second = first.plusSeconds(3_600)

    assertEquals(first.toEpochMilli(), deleteExtraFor(first))
    assertEquals(second.toEpochMilli(), deleteExtraFor(second))
  }

  // The assertion that actually pins the device behaviour: Robolectric replaces a re-used
  // PendingIntent's extras where the platform keeps the old ones, so only the flag distinguishes a
  // build that stays correct on the second timer from one that silently keeps the first timer's
  // start time.
  @Test
  fun `the delete intent can be re-issued with new extras`() {
    notifier.onTimerStateChanged(runningState())
    val deleteIntent = requireNotNull(activeNotification().deleteIntent) { "no delete intent" }

    assertTrue(shadowOf(deleteIntent).flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
  }

  @Test
  fun `a delete intent carrying no start time leaves the dismissed entry alone`() {
    val dismissed = Instant.parse("2026-07-31T09:00:00Z")
    DismissedLiveUpdate.dismiss(context, dismissed.toEpochMilli())

    LiveUpdateDismissedReceiver()
      .onReceive(context, Intent(context, LiveUpdateDismissedReceiver::class.java))

    assertTrue(DismissedLiveUpdate.isDismissed(context, dismissed.toEpochMilli()))
  }

  @Test
  @Config(sdk = [35])
  fun `before Wear 7 the notification does not request promotion`() {
    notifier.onTimerStateChanged(runningState())

    assertEquals(1, activeNotifications().size)
    assertFalse(NotificationCompat.isRequestPromotedOngoing(activeNotification()))
  }

  @Test
  @Config(sdk = [35])
  fun `before Wear 7 a dismissed entry still posts, since only Live Updates can be dismissed`() {
    val start = Instant.parse("2026-07-31T09:00:00Z")
    DismissedLiveUpdate.dismiss(context, start.toEpochMilli())

    notifier.onTimerStateChanged(runningState(startInstant = start))

    assertEquals(1, activeNotifications().size)
  }

  @Test
  fun `the legacy stopwatch is anchored in elapsedRealtime, not in epoch millis`() {
    val start = Instant.parse("2026-07-31T09:00:00Z")
    val now = start.plusSeconds(150)
    val bootRelativeNow = 1_000_000L

    // 150s before "now" in the boot-relative domain. Handing StopwatchPart start.toEpochMilli()
    // instead is the bug this pins: it underflows to a hugely negative elapsed time on device.
    assertEquals(bootRelativeNow - 150_000L, stopwatchTimeZeroMillis(start, now, bootRelativeNow))
  }
}
