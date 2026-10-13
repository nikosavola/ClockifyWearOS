package fi.nikosavola.clockifywear.data

/**
 * Reversible no-crypto stand-in for [ApiKeyCipher]: the real Android Keystore provider isn't
 * registered under Robolectric/JVM unit tests, so [AndroidKeystoreApiKeyCipher] can't run here.
 */
class FakeApiKeyCipher : ApiKeyCipher {
  /** How many decrypts actually ran, for tests that pin what SettingsStore caches. */
  var decryptCount = 0
    private set

  override fun encrypt(plaintext: String): String = "$PREFIX$plaintext"

  override fun decrypt(ciphertext: String): String? {
    decryptCount++
    return ciphertext.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
  }

  private companion object {
    const val PREFIX = "fake-encrypted:"
  }
}
