package fi.nikosavola.clockifywear.data

/** Result of a repository operation: never thrown for the expected API/network failure modes. */
sealed interface ClockifyResult<out T> {
  data class Success<out T>(val value: T) : ClockifyResult<T>

  data class Failure(val error: ClockifyError) : ClockifyResult<Nothing>
}

/** Failure reasons a repository call can return, mapped from the underlying HTTP/IO exception. */
sealed interface ClockifyError {
  /** HTTP 401: the stored API key is invalid or was revoked. The UI should route to Settings. */
  data object Unauthorized : ClockifyError

  /** HTTP 429 surfaced after the client's single built-in retry already failed once more. */
  data object RateLimited : ClockifyError

  /** No network reachable, or the request otherwise failed at the transport layer. */
  data object Offline : ClockifyError

  /** Any other non-2xx response, carrying the status code for diagnostics. */
  data class Http(val code: Int) : ClockifyError

  /** The response body could not be decoded as the expected DTO shape. */
  data object ParseError : ClockifyError

  /** Sign-in succeeded but the account has no active/default workspace and none was returned. */
  data object NoWorkspaceFound : ClockifyError

  /** The operation needs a signed-in user (workspaceId/userId) but none is persisted yet. */
  data object NotSignedIn : ClockifyError
}
