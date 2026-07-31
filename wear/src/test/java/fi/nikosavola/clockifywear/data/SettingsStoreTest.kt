package fi.nikosavola.clockifywear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var store: SettingsStore

  @Before
  fun setUp() {
    val dataStore =
      PreferenceDataStoreFactory.create(
        produceFile = { tempFolder.newFile("settings.preferences_pb") }
      )
    store = SettingsStore(dataStore)
  }

  @Test
  fun `all fields round-trip through settings`() = runTest {
    store.setApiKey("key-1")
    store.setUserId("user-1")
    store.setWorkspaceId("ws-1")
    store.setDefaultProjectId("proj-1")
    store.setDefaultTaskId("task-1")

    val settings = store.currentSettings()

    assertEquals("key-1", settings.apiKey)
    assertEquals("user-1", settings.userId)
    assertEquals("ws-1", settings.workspaceId)
    assertEquals("proj-1", settings.defaultProjectId)
    assertEquals("task-1", settings.defaultTaskId)
  }

  @Test
  fun `unset fields default to null`() = runTest {
    val settings = store.currentSettings()

    assertNull(settings.apiKey)
    assertNull(settings.userId)
    assertNull(settings.workspaceId)
    assertNull(settings.defaultProjectId)
    assertNull(settings.defaultTaskId)
  }

  @Test
  fun `clear resets every field`() = runTest {
    store.setApiKey("key-1")
    store.setUserId("user-1")
    store.setWorkspaceId("ws-1")

    store.clear()

    val settings = store.currentSettings()
    assertNull(settings.apiKey)
    assertNull(settings.userId)
    assertNull(settings.workspaceId)
  }

  @Test
  fun `setApiKey with null removes the stored key`() = runTest {
    store.setApiKey("key-1")
    store.setApiKey(null)

    assertNull(store.currentSettings().apiKey)
  }

  @Test
  fun `apiKeySupplier reflects the latest key synchronously without collecting settings`() =
    runTest {
      assertNull(store.apiKeySupplier())

      store.setApiKey("key-1")

      // No `.first()`/collection on `settings` happened here: setApiKey alone must keep the
      // synchronous supplier current, since that is the whole point of the cache bridge.
      assertEquals("key-1", store.apiKeySupplier())
    }

  @Test
  fun `apiKeySupplier is primed by reading settings after a cold start`() = runTest {
    // DataStore allows only one live instance per file, so this reuses the same DataStore to
    // simulate the scenario without opening the file a second time: a fresh SettingsStore
    // wrapping already-populated data starts with an un-primed in-memory cache until something
    // reads `settings`, exactly as it would after a real process restart.
    val dataStore =
      PreferenceDataStoreFactory.create(
        produceFile = { tempFolder.newFile("restart.preferences_pb") }
      )
    SettingsStore(dataStore).setApiKey("saved-key")

    val restarted = SettingsStore(dataStore)
    assertNull(restarted.apiKeySupplier())

    assertEquals("saved-key", restarted.currentSettings().apiKey)
    assertEquals("saved-key", restarted.apiKeySupplier())
  }
}
