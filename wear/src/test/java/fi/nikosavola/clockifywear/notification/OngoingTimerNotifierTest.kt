package fi.nikosavola.clockifywear.notification

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.ui.timer.TimerUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"

@RunWith(RobolectricTestRunner::class)
class OngoingTimerNotifierTest {
  private lateinit var context: Context
  private lateinit var notifier: OngoingTimerNotifier

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // API 33+ requires POST_NOTIFICATIONS to actually post; Robolectric doesn't grant it just
    // because the manifest declares it, so it must be granted explicitly here.
    shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    notifier = OngoingTimerNotifier(context)
  }

  private fun runningState(startInstant: Instant = Instant.parse("2026-07-31T09:00:00Z")) =
    TimerUiState.Running(
      projectId = PROJECT_ID,
      projectName = "Website",
      projectColor = null,
      startInstant = startInstant,
      elapsedSeconds = 0,
      description = null,
    )

  @Test
  fun `Running state posts an active notification`() {
    notifier.onTimerStateChanged(runningState())

    val active = NotificationManagerCompat.from(context).activeNotifications
    assertEquals(1, active.size)
  }

  @Test
  fun `Idle state after Running cancels the notification`() {
    notifier.onTimerStateChanged(runningState())
    assertEquals(1, NotificationManagerCompat.from(context).activeNotifications.size)

    notifier.onTimerStateChanged(TimerUiState.Idle(hasDefaultProject = false))

    assertTrue(NotificationManagerCompat.from(context).activeNotifications.isEmpty())
  }
}
