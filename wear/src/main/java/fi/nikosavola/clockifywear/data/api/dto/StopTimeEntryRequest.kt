package fi.nikosavola.clockifywear.data.api.dto

import fi.nikosavola.clockifywear.data.api.InstantSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class StopTimeEntryRequest(@Serializable(with = InstantSerializer::class) val end: Instant)
