package fi.nikosavola.clockifywear.data.api

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Clockify returns fractional seconds sometimes but only accepts whole-second precision on
// write, so encode and decode are deliberately asymmetric: Instant.parse tolerates fractional
// input, truncation guarantees valid output.
object InstantSerializer : KSerializer<Instant> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Instant) {
    encoder.encodeString(value.truncatedTo(ChronoUnit.SECONDS).toString())
  }

  override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
