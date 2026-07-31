package fi.nikosavola.clockifywear.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.data.SettingsStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  // ClockifyRepository.workspaces() does not call settingsStore.currentSettings() before firing
  // (unlike every identity-requiring operation), so it is exactly the request that would go out
  // with no X-Api-Key header on a cold start if nothing primed the in-memory key mirror first (see
  // SettingsStore.apiKeySupplier's doc and HANDOFF.md section 4). AppContainer wraps the SAME
  // DataStore in a fresh SettingsStore (mirroring SettingsStoreTest's own cold-start test), so its
  // cachedApiKey starts null even though a key is already on disk, exactly as after a process
  // restart. Awaiting settingsPrimed before the request proves the fix.
  @Test
  fun `settingsPrimed primes the api key before a cold-start workspaces request`() = runTest {
    val dataStore =
      PreferenceDataStoreFactory.create(
        produceFile = { tempFolder.newFile("settings.preferences_pb") }
      )
    SettingsStore(dataStore).apply {
      setApiKey("cold-start-key")
      setWorkspaceId("ws-1")
    }

    val context = ApplicationProvider.getApplicationContext<Context>()
    val container =
      AppContainer(context, baseUrl = server.url("/").toString(), dataStore = dataStore)
    container.settingsPrimed.await()

    server.enqueue(MockResponse().setBody("[]"))
    container.repository.workspaces()

    val request = server.takeRequest()
    assertEquals("cold-start-key", request.getHeader("X-Api-Key"))
  }
}
