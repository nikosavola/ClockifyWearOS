package fi.nikosavola.clockifywear.companion

import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.UserDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REQUEST_ID = "req-1"

// Plain JVM unit test: ApiKeyMessageDispatcher takes plain suspend lambdas via
// CompanionSignInEnvironment, no Android/Robolectric dependency or real WearableListenerService
// needed - mirrors ApiKeyMessageResolverTest and TileClickResolverTest.
class ApiKeyMessageDispatcherTest {
  private val dispatcher = ApiKeyMessageDispatcher()

  private fun environment(
    isAlreadySignedIn: Boolean = false,
    onOrder: MutableList<String>? = null,
    onSignedIn: () -> Unit = {},
    signIn: suspend (String) -> ClockifyResult<UserDto> = {
      ClockifyResult.Success(UserDto(id = "u1", email = "user@example.com"))
    },
  ) =
    CompanionSignInEnvironment(
      awaitSettingsPrimed = { onOrder?.add("settingsPrimed") },
      isAlreadySignedIn = {
        onOrder?.add("isAlreadySignedIn")
        isAlreadySignedIn
      },
      signIn = { apiKey ->
        onOrder?.add("signIn")
        signIn(apiKey)
      },
      onSignedIn = onSignedIn,
    )

  @Test
  fun `settingsPrimed is awaited before checking sign-in state or calling signIn`() = runTest {
    val order = mutableListOf<String>()

    dispatcher.dispatch(
      apiKeyRequestPath(REQUEST_ID),
      "key".toByteArray(),
      environment(onOrder = order),
    )

    assertEquals(listOf("settingsPrimed", "isAlreadySignedIn", "signIn"), order)
  }

  @Test
  fun `a path this app doesn't answer returns null and touches nothing`() = runTest {
    val order = mutableListOf<String>()
    val paths =
      listOf(
        signInSuccessPath(REQUEST_ID),
        signInFailurePath(REQUEST_ID),
        "/clockify/api-key-request",
        "/other",
        "",
      )

    val replies = paths.map { dispatcher.dispatch(it, ByteArray(0), environment(onOrder = order)) }

    assertTrue(replies.all { it == null })
    assertTrue(order.isEmpty())
  }

  @Test
  fun `a matching request echoes the request id on success`() = runTest {
    val requestId = "req-with/a-slash"

    val reply =
      dispatcher.dispatch(apiKeyRequestPath(requestId), "key".toByteArray(), environment())

    assertEquals(signInSuccessPath(requestId), reply?.path)
  }

  @Test
  fun `a matching request echoes the request id on failure`() = runTest {
    val env = environment(signIn = { ClockifyResult.Failure(ClockifyError.Unauthorized) })

    val reply = dispatcher.dispatch(apiKeyRequestPath(REQUEST_ID), "key".toByteArray(), env)

    assertEquals(signInFailurePath(REQUEST_ID), reply?.path)
  }

  @Test
  fun `tile refresh fires exactly once on success`() = runTest {
    var refreshCount = 0

    dispatcher.dispatch(
      apiKeyRequestPath(REQUEST_ID),
      "key".toByteArray(),
      environment(onSignedIn = { refreshCount++ }),
    )

    assertEquals(1, refreshCount)
  }

  @Test
  fun `tile refresh never fires on a rejected key`() = runTest {
    var refreshCount = 0
    val env =
      environment(
        onSignedIn = { refreshCount++ },
        signIn = { ClockifyResult.Failure(ClockifyError.Unauthorized) },
      )

    dispatcher.dispatch(apiKeyRequestPath(REQUEST_ID), "key".toByteArray(), env)

    assertEquals(0, refreshCount)
  }

  @Test
  fun `tile refresh never fires when the watch was already signed in`() = runTest {
    var refreshCount = 0
    val env = environment(isAlreadySignedIn = true, onSignedIn = { refreshCount++ })

    val reply = dispatcher.dispatch(apiKeyRequestPath(REQUEST_ID), "key".toByteArray(), env)

    assertEquals(0, refreshCount)
    assertEquals(
      CompanionSignInErrorCode.ALREADY_SIGNED_IN.name,
      reply?.payload?.let { String(it, Charsets.UTF_8) },
    )
  }

  @Test
  fun `the api key reaches signIn byte-identically, including whitespace and non-ASCII`() =
    runTest {
      val key = "  clé-δοκιμή-key  "
      var receivedKey: String? = null
      val env =
        environment(
          signIn = { apiKey ->
            receivedKey = apiKey
            ClockifyResult.Success(UserDto(id = "u1"))
          }
        )

      dispatcher.dispatch(apiKeyRequestPath(REQUEST_ID), key.toByteArray(Charsets.UTF_8), env)

      assertEquals(key, receivedKey)
    }

  @Test
  fun `an empty payload is still forwarded to signIn`() = runTest {
    var receivedKey: String? = null
    val env =
      environment(
        signIn = { apiKey ->
          receivedKey = apiKey
          ClockifyResult.Success(UserDto(id = "u1"))
        }
      )

    dispatcher.dispatch(apiKeyRequestPath(REQUEST_ID), ByteArray(0), env)

    assertEquals("", receivedKey)
  }
}
