package fi.nikosavola.clockifywear.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ProjectCache
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.CLOCKIFY_BASE_URL
import fi.nikosavola.clockifywear.data.api.createClockifyApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

private const val SETTINGS_DATASTORE_FILE_NAME = "settings.preferences_pb"
private const val PROJECT_CACHE_FILE_NAME = "projects.json"

/**
 * Manual DI root: no Hilt/Koin, matches PLANNING.md. Built once by
 * [fi.nikosavola.clockifywear.ClockifyApp] and handed down to composables.
 *
 * @param context used only to locate [Context.getFilesDir]; not retained.
 * @param baseUrl overridable so tests can point the client at a MockWebServer instance.
 * @param dataStore overridable so tests can reuse one DataStore instance across two
 *   [SettingsStore]s to simulate a cold restart (see [settingsPrimed] below); production always
 *   uses the default.
 */
class AppContainer(
  context: Context,
  baseUrl: String = CLOCKIFY_BASE_URL,
  dataStore: DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
      produceFile = { File(context.filesDir, SETTINGS_DATASTORE_FILE_NAME) }
    ),
) {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val settingsStore: SettingsStore = SettingsStore(dataStore)

  private val projectCache: ProjectCache =
    ProjectCache(File(context.filesDir, PROJECT_CACHE_FILE_NAME))

  private val api = createClockifyApi(apiKey = settingsStore.apiKeySupplier, baseUrl = baseUrl)

  val repository: ClockifyRepository = ClockifyRepository(api, settingsStore, projectCache)

  // SettingsStore.apiKeySupplier reads an in-memory mirror that starts null until something reads
  // `settings`. Most repository calls prime it as a side effect (they read currentSettings() for
  // workspaceId/userId before firing), but ClockifyRepository.workspaces() does not, so a cold
  // start could otherwise send that request with no X-Api-Key header and 401 the user into
  // Settings spuriously. Priming here, once, up front removes the ordering dependency on which
  // repository call happens to go first. ViewModels await this before their first repository call.
  val settingsPrimed: Deferred<Settings> = applicationScope.async {
    settingsStore.currentSettings()
  }
}
