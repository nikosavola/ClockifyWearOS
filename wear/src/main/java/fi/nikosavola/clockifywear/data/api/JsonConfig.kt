package fi.nikosavola.clockifywear.data.api

import kotlinx.serialization.json.Json

// Shared between the Retrofit converter and any standalone (de)serialization, e.g. tests.
// explicitNulls = false so optional request fields are omitted rather than sent as JSON null.
val clockifyJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}
