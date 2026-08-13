package fi.nikosavola.clockifywear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * All persisted settings as one value. A single [Flow] of this data class is exposed instead of
 * separate flows per field: repository code almost always needs workspaceId and userId together,
 * and combining several independent flows at every call site would be more boilerplate than one
 * read.
 */
data class Settings(
  val apiKey: String? = null,
  val userId: String? = null,
  val workspaceId: String? = null,
  val defaultProjectId: String? = null,
  val defaultTaskId: String? = null,
  val email: String? = null,
  // See TileClickResolver: durable, not just an in-memory field on the service, because the replay
  // window this guards against (a stale click id redelivered on a later refresh) can span the
  // Tile service process being evicted and restarted in between.
  val lastConsumedTileClickToken: String? = null,
)

/**
 * Wraps a Preferences [DataStore] supplied by the caller (never a `Context` directly) so tests can
 * point it at a temp file.
 *
 * The API key is stored unencrypted. `androidx.security.crypto` is deprecated and Keystore-backed
 * encryption is low value for a device already gated by its own lock screen; accepted trade-off.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {
  private val apiKeyKey = stringPreferencesKey("api_key")
  private val userIdKey = stringPreferencesKey("user_id")
  private val workspaceIdKey = stringPreferencesKey("workspace_id")
  private val defaultProjectIdKey = stringPreferencesKey("default_project_id")
  private val defaultTaskIdKey = stringPreferencesKey("default_task_id")
  private val emailKey = stringPreferencesKey("email")
  private val lastConsumedTileClickTokenKey = stringPreferencesKey("last_consumed_tile_click_token")

  // createClockifyApi needs a synchronous `() -> String?` supplier for its OkHttp interceptor,
  // which runs on an OkHttp dispatcher thread and must never block on a DataStore Flow. This
  // field mirrors the latest known key so the supplier can read it synchronously; it is kept
  // current by every read of `settings` and every write of the key through this class. A caller
  // that writes the key only via `setApiKey` and otherwise never collects `settings` (e.g. a
  // future DI container at cold start) should collect `settings` once, or call
  // `currentSettings()`, before issuing the first authenticated request so this cache is primed.
  @Volatile private var cachedApiKey: String? = null

  val settings: Flow<Settings> =
    dataStore.data.map { prefs ->
      Settings(
          apiKey = prefs[apiKeyKey],
          userId = prefs[userIdKey],
          workspaceId = prefs[workspaceIdKey],
          defaultProjectId = prefs[defaultProjectIdKey],
          defaultTaskId = prefs[defaultTaskIdKey],
          email = prefs[emailKey],
          lastConsumedTileClickToken = prefs[lastConsumedTileClickTokenKey],
        )
        .also { cachedApiKey = it.apiKey }
    }

  /** Synchronous bridge for `createClockifyApi`'s `apiKey: () -> String?` parameter. */
  val apiKeySupplier: () -> String? = { cachedApiKey }

  suspend fun currentSettings(): Settings = settings.first()

  suspend fun setApiKey(apiKey: String?) {
    cachedApiKey = apiKey
    setOrRemove(apiKeyKey, apiKey)
  }

  suspend fun setUserId(userId: String?) = setOrRemove(userIdKey, userId)

  suspend fun setWorkspaceId(workspaceId: String?) = setOrRemove(workspaceIdKey, workspaceId)

  suspend fun setDefaultProjectId(projectId: String?) = setOrRemove(defaultProjectIdKey, projectId)

  suspend fun setDefaultTaskId(taskId: String?) = setOrRemove(defaultTaskIdKey, taskId)

  suspend fun setEmail(email: String?) = setOrRemove(emailKey, email)

  suspend fun setLastConsumedTileClickToken(token: String?) =
    setOrRemove(lastConsumedTileClickTokenKey, token)

  /** Clears all persisted settings, e.g. on sign-out. */
  suspend fun clear() {
    cachedApiKey = null
    dataStore.edit { it.clear() }
  }

  private suspend fun setOrRemove(key: Preferences.Key<String>, value: String?) {
    dataStore.edit { prefs -> if (value == null) prefs.remove(key) else prefs[key] = value }
  }
}
