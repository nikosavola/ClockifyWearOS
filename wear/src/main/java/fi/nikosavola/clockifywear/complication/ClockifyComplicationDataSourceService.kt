package fi.nikosavola.clockifywear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import fi.nikosavola.clockifywear.ClockifyApp
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.ui.MainActivity

/**
 * SHORT_TEXT watch face complication showing the same "current project + live elapsed time" the
 * Tile shows. Bound on demand by the system, possibly with the main app process already evicted, so
 * state is always read fresh from [ClockifyRepository] here rather than trusted from any in-memory
 * ViewModel - same reasoning as [fi.nikosavola.clockifywear.tile.ClockifyTileService].
 */
class ClockifyComplicationDataSourceService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    val repository = (applicationContext as ClockifyApp).appContainer.repository
    val entry = (repository.fetchRunningEntry() as? ClockifyResult.Success)?.value

    return if (entry != null) {
      val projectName = entry.projectId?.let { resolveProjectName(repository, it) }
      val fallbackTitle =
        projectName ?: entry.projectId ?: getString(R.string.timer_no_project_label)
      // CountUpTimeReference takes a plain java.time.Instant, unlike
      // androidx.wear.ongoing.Status.StopwatchPart's SystemClock.elapsedRealtime() convention (see
      // OngoingTimerNotifier) - confirmed via decompile, no elapsedRealtime conversion needed here.
      ShortTextComplicationData.Builder(
          text =
            TimeDifferenceComplicationText.Builder(
                TimeDifferenceStyle.STOPWATCH,
                CountUpTimeReference(entry.timeInterval.start),
              )
              .build(),
          contentDescription = PlainComplicationText.Builder(fallbackTitle).build(),
        )
        .setTitle(PlainComplicationText.Builder(fallbackTitle).build())
        .setTapAction(tapAction())
        .build()
    } else {
      val idleText = getString(R.string.complication_idle_label)
      ShortTextComplicationData.Builder(
          text = PlainComplicationText.Builder(idleText).build(),
          contentDescription = PlainComplicationText.Builder(idleText).build(),
        )
        .setTapAction(tapAction())
        .build()
    }
  }

  override fun getPreviewData(type: ComplicationType): ComplicationData? {
    if (type != ComplicationType.SHORT_TEXT) return null
    // ShortTextComplicationData's text/title fields have roughly a 7-character display budget on
    // conforming pickers, so preview values are kept short rather than mirroring the live
    // HH:MM:SS/full project name shown in onComplicationRequest.
    val previewTime = getString(R.string.complication_preview_time)
    val previewProject = getString(R.string.complication_preview_project)
    return ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(previewTime).build(),
        contentDescription = PlainComplicationText.Builder("$previewProject, $previewTime").build(),
      )
      .setTitle(PlainComplicationText.Builder(previewProject).build())
      .build()
  }

  private fun tapAction(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )

  // Cosmetic-only lookup: a failed or unresolved project must never crash the complication, same
  // tolerance as TimerViewModel.resolveProject. Falls back to the raw id, not an error state.
  private suspend fun resolveProjectName(
    repository: ClockifyRepository,
    projectId: String,
  ): String? =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value.firstOrNull { it.id == projectId }?.name
      is ClockifyResult.Failure -> null
    }
}
