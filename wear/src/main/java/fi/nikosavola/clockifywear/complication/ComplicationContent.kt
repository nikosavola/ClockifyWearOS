package fi.nikosavola.clockifywear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.ui.MainActivity

/**
 * Builds the complication's data for one request. Separate from
 * [ClockifyComplicationDataSourceService] so the running-versus-idle shape and the preview can be
 * exercised without a bound service.
 */
class ComplicationContent(private val context: Context) {
  /**
   * SHORT_TEXT watch face complication showing the same "current project + live elapsed time" the
   * Tile shows. State is always read fresh from [repository] here rather than trusted from any
   * in-memory ViewModel, since this is bound on demand with the main app process possibly evicted.
   */
  suspend fun data(repository: ClockifyRepository): ComplicationData? {
    val entry = (repository.fetchRunningEntry() as? ClockifyResult.Success)?.value

    return if (entry != null) {
      val projectName = entry.projectId?.let { resolveProjectName(repository, it) }
      val fallbackTitle =
        projectName ?: entry.projectId ?: context.getString(R.string.timer_no_project_label)
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
      val idleText = context.getString(R.string.complication_idle_label)
      ShortTextComplicationData.Builder(
          text = PlainComplicationText.Builder(idleText).build(),
          contentDescription = PlainComplicationText.Builder(idleText).build(),
        )
        .setTapAction(tapAction())
        .build()
    }
  }

  fun preview(type: ComplicationType): ComplicationData? {
    if (type != ComplicationType.SHORT_TEXT) return null
    // ShortTextComplicationData's text/title fields have roughly a 7-character display budget on
    // conforming pickers, so preview values are kept short rather than mirroring the live
    // HH:MM:SS/full project name shown in onComplicationRequest.
    val previewTime = context.getString(R.string.complication_preview_time)
    val previewProject = context.getString(R.string.complication_preview_project)
    return ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(previewTime).build(),
        contentDescription = PlainComplicationText.Builder("$previewProject, $previewTime").build(),
      )
      .setTitle(PlainComplicationText.Builder(previewProject).build())
      .build()
  }

  private fun tapAction(): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java),
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
