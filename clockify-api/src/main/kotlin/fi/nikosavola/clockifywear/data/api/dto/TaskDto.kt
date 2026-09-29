package fi.nikosavola.clockifywear.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class TaskDto(val id: String, val name: String, val status: String? = null)
