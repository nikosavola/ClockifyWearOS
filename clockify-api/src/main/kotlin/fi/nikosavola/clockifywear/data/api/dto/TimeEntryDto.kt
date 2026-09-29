package fi.nikosavola.clockifywear.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class TimeEntryDto(
  val id: String,
  val description: String? = null,
  val projectId: String? = null,
  val taskId: String? = null,
  val billable: Boolean = false,
  val timeInterval: TimeIntervalDto,
)
