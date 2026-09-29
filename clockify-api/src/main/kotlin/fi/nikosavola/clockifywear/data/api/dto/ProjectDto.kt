package fi.nikosavola.clockifywear.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
  val id: String,
  val name: String,
  val color: String? = null,
  val archived: Boolean = false,
  val clientName: String? = null,
)
