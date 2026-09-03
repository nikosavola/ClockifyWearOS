package fi.nikosavola.clockifywear.companion

import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.UserDto

/** Outcome of resolving an incoming companion sign-in request; see [ApiKeyMessageResolver]. */
sealed interface CompanionSignInOutcome {
  data class Success(val email: String?) : CompanionSignInOutcome

  data class Failure(val errorCode: CompanionSignInErrorCode) : CompanionSignInOutcome
}

/**
 * Resolves a phone-companion sign-in request through the exact same sign-in path the watch's own
 * Settings screen uses ([signIn], normally `ClockifyRepository::signIn`), kept separate from
 * [ApiKeyMessageListenerService] so it's testable without a real Android `Service` - mirrors
 * [fi.nikosavola.clockifywear.tile.TileClickResolver]'s split between thin service and plain
 * resolver.
 */
class ApiKeyMessageResolver {
  suspend fun resolve(
    apiKey: String,
    isAlreadySignedIn: suspend () -> Boolean,
    signIn: suspend (String) -> ClockifyResult<UserDto>,
  ): CompanionSignInOutcome {
    // A companion request never overwrites a working session: ClockifyRepository.signIn clears
    // the stored key outright on any failure (including a simple typo), which would otherwise sign
    // an already-working watch out just because the phone sent a bad key.
    if (isAlreadySignedIn()) {
      return CompanionSignInOutcome.Failure(CompanionSignInErrorCode.ALREADY_SIGNED_IN)
    }
    return when (val result = signIn(apiKey)) {
      is ClockifyResult.Success -> CompanionSignInOutcome.Success(result.value.email)
      is ClockifyResult.Failure ->
        CompanionSignInOutcome.Failure(result.error.toCompanionErrorCode())
    }
  }
}

/** Pure so [ApiKeyMessageListenerService] doesn't need a real `MessageClient` to be tested. */
fun CompanionSignInOutcome.toReplyPath(requestId: String): String =
  when (this) {
    is CompanionSignInOutcome.Success -> signInSuccessPath(requestId)
    is CompanionSignInOutcome.Failure -> signInFailurePath(requestId)
  }

fun CompanionSignInOutcome.toReplyPayload(): ByteArray =
  when (this) {
    is CompanionSignInOutcome.Success -> email.orEmpty().toByteArray(Charsets.UTF_8)
    is CompanionSignInOutcome.Failure -> errorCode.name.toByteArray(Charsets.UTF_8)
  }
