package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.InstantSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TimeIntervalDto(
  @Serializable(with = InstantSerializer::class) val start: Instant,
  @Serializable(with = InstantSerializer::class) val end: Instant? = null,
)
