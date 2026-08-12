package fi.nikosavola.clockifywear.tile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.ui.timer.TimerUiState
import java.time.Instant
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PROJECT_ID = "5f8a1b2c3d4e5f6a7b8c9d20"

/**
 * TileService/ComplicationDataSourceUpdateRequester cross a Binder boundary to real system services
 * that don't exist in a unit-test JVM, so Robolectric can't assert the actual Tile/complication
 * update mechanics fired. What's honestly testable at this layer: the method doesn't throw for any
 * [TimerUiState] variant TimerViewModel.applyEntry can actually emit ([TimerUiState.Running]/
 * [TimerUiState.Idle]), same ceiling OngoingTimerNotifierTest accepts for its own
 * system-integration surface.
 */
@RunWith(RobolectricTestRunner::class)
class TileUpdaterTest {
  private lateinit var context: Context
  private lateinit var updater: TileUpdater

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    updater = TileUpdater(context)
  }

  private fun runningState() =
    TimerUiState.Running(
      projectId = PROJECT_ID,
      projectName = "Website",
      projectColor = null,
      startInstant = Instant.parse("2026-07-31T09:00:00Z"),
      elapsedSeconds = 0,
      description = null,
    )

  @Test
  fun `Running state does not throw`() {
    updater.onTimerStateChanged(runningState())
  }

  @Test
  fun `Idle state does not throw`() {
    updater.onTimerStateChanged(TimerUiState.Idle(hasDefaultProject = false))
  }

  @Test
  fun `two consecutive Running updates do not throw`() {
    updater.onTimerStateChanged(runningState())
    updater.onTimerStateChanged(runningState())
  }
}
