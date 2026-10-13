package fi.nikosavola.clockifywear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: SettingsStore

  @Before
  fun setUp() {
    dataStore =
      PreferenceDataStoreFactory.create(
        produceFile = { tempFolder.newFile("settings.preferences_pb") }
      )
    store = SettingsStore(dataStore, FakeApiKeyCipher())
  }

  @Test
  fun `all fields round-trip through settings`() = runTest {
    store.setApiKey("key-1")
    store.setUserId("user-1")
    store.setWorkspaceId("ws-1")
    store.setDefaultProjectId("proj-1")
    store.setDefaultTaskId("task-1")
    store.setEmail("user@example.com")

    val settings = store.currentSettings()

    assertEquals("key-1", settings.apiKey)
    assertEquals("user-1", settings.userId)
    assertEquals("ws-1", settings.workspaceId)
    assertEquals("proj-1", settings.defaultProjectId)
    assertEquals("task-1", settings.defaultTaskId)
    assertEquals("user@example.com", settings.email)
  }

  @Test
  fun `unset fields default to null`() = runTest {
    val settings = store.currentSettings()

    assertNull(settings.apiKey)
    assertNull(settings.userId)
    assertNull(settings.workspaceId)
    assertNull(settings.defaultProjectId)
    assertNull(settings.defaultTaskId)
    assertNull(settings.email)
  }

  @Test
  fun `setEmail with null removes the stored email`() = runTest {
    store.setEmail("user@example.com")
    store.setEmail(null)

    assertNull(store.currentSettings().email)
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
  fun `api key is passed through apiKeyCipher before it reaches the DataStore`() = runTest {
    store.setApiKey("secret-key")

    val raw = dataStore.data.first()[stringPreferencesKey("api_key")]

    assertNotEquals("secret-key", raw)
    assertEquals("secret-key", FakeApiKeyCipher().decrypt(raw!!))
  }

  @Test
  fun `reading settings twice decrypts the key only once`() = runTest {
    val cipher = FakeApiKeyCipher()
    val store = SettingsStore(dataStore, cipher)
    store.setApiKey("key-1")

    store.currentSettings()
    store.currentSettings()

    assertEquals(1, cipher.decryptCount)
  }

  @Test
  fun `a changed key is decrypted again rather than served from the cache`() = runTest {
    val cipher = FakeApiKeyCipher()
    val store = SettingsStore(dataStore, cipher)
    store.setApiKey("key-1")
    assertEquals("key-1", store.currentSettings().apiKey)

    store.setApiKey("key-2")

    assertEquals("key-2", store.currentSettings().apiKey)
    assertEquals(2, cipher.decryptCount)
  }

  @Test
  fun `a decrypt that fails is retried on the next read rather than cached`() = runTest {
    val cipher = FakeApiKeyCipher()
    val store = SettingsStore(dataStore, cipher)
    // A stored value the cipher rejects: what a corrupt record or a wiped keystore looks like.
    dataStore.edit { it[stringPreferencesKey("api_key")] = "not-encrypted-by-this-cipher" }

    assertNull(store.currentSettings().apiKey)
    assertNull(store.currentSettings().apiKey)

    assertEquals(2, cipher.decryptCount)
  }

  // Signing out has to drop the decrypted key along with the stored one: the cache is keyed on the
  // ciphertext, so a key that encrypts to the same bytes would otherwise be served straight back
  // from memory without the cipher ever seeing it again.
  @Test
  fun `clear makes the next read decrypt the key again`() = runTest {
    val cipher = FakeApiKeyCipher()
    val store = SettingsStore(dataStore, cipher)
    store.setApiKey("key-1")
    assertEquals("key-1", store.currentSettings().apiKey)

    store.clear()
    store.setApiKey("key-1")

    assertEquals("key-1", store.currentSettings().apiKey)
    assertEquals(2, cipher.decryptCount)
  }

  // The store can fail where the cipher did not, so the mirror has to stay untouched for that half
  // too: apiKeySupplier hands whatever is in it straight to the next request.
  @Test
  fun `a key the store could not write is not left in the mirror`() = runBlocking {
    val blocked = tempFolder.newFile("blocked")
    val store =
      SettingsStore(
        PreferenceDataStoreFactory.create(
          produceFile = { File(blocked, "settings.preferences_pb") }
        ),
        FakeApiKeyCipher(),
      )

    val failure = runCatching { store.setApiKey("key-1") }.exceptionOrNull()

    assertNotNull("the store should have rejected this write", failure)
    assertNull(store.apiKeySupplier())
  }

  // A re-key that cannot be stored leaves the account that was already signed in alone, on the key
  // it was using: locking a working watch out of itself over one failed write would be worse than
  // the error the caller reports.
  @Test
  fun `a re-key that cannot be stored keeps the key that was already working`() = runTest {
    var failNextEncrypt = false
    val real = FakeApiKeyCipher()
    val store =
      SettingsStore(
        dataStore,
        object : ApiKeyCipher {
          override fun encrypt(plaintext: String): String {
            if (failNextEncrypt) throw IOException("keystore unavailable")
            return real.encrypt(plaintext)
          }

          override fun decrypt(ciphertext: String): String? = real.decrypt(ciphertext)
        },
      )
    store.setApiKey("old-key")
    assertEquals("old-key", store.apiKeySupplier())

    failNextEncrypt = true
    val failure = runCatching { store.setApiKey("new-key") }.exceptionOrNull()

    assertNotNull("the keystore should have rejected this write", failure)
    assertEquals("old-key", store.apiKeySupplier())
    assertEquals("old-key", store.currentSettings().apiKey)
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
    SettingsStore(dataStore, FakeApiKeyCipher()).setApiKey("saved-key")

    val restarted = SettingsStore(dataStore, FakeApiKeyCipher())
    assertNull(restarted.apiKeySupplier())

    assertEquals("saved-key", restarted.currentSettings().apiKey)
    assertEquals("saved-key", restarted.apiKeySupplier())
  }
}
