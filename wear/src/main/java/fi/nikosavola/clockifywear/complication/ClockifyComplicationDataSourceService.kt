package fi.nikosavola.clockifywear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import fi.nikosavola.clockifywear.ClockifyApp

/**
 * SHORT_TEXT watch face complication showing the same "current project + live elapsed time" the
 * Tile shows. Content itself is [ComplicationContent]'s job; this class is only the service
 * contract around it.
 */
class ClockifyComplicationDataSourceService : SuspendingComplicationDataSourceService() {
  private val content = ComplicationContent(this)

  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
    content.data((applicationContext as ClockifyApp).appContainer.repository)

  override fun getPreviewData(type: ComplicationType): ComplicationData? = content.preview(type)
}
