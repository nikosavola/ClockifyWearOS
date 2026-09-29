package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.InstantSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class StartTimeEntryRequest(
  @Serializable(with = InstantSerializer::class) val start: Instant,
  val projectId: String? = null,
  val taskId: String? = null,
  val description: String? = null,
  val billable: Boolean? = null,
)
