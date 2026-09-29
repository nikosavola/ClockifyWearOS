package fi.nikosavola.clockifywear.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
  val id: String,
  val email: String? = null,
  val name: String? = null,
  val activeWorkspace: String? = null,
  val defaultWorkspace: String? = null,
)
