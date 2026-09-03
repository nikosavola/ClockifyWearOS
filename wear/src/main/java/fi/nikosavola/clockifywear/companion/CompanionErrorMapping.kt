package fi.nikosavola.clockifywear.companion

import fi.nikosavola.clockifywear.data.ClockifyError

/**
 * Maps a repository failure to the [CompanionSignInErrorCode] sent over the wire. Exhaustive (no
 * `else`) so a new [ClockifyError] variant fails to compile here until it's given a code, instead
 * of silently falling through to [CompanionSignInErrorCode.UNKNOWN] on the wear side too.
 */
fun ClockifyError.toCompanionErrorCode(): CompanionSignInErrorCode =
  when (this) {
    is ClockifyError.Unauthorized -> CompanionSignInErrorCode.UNAUTHORIZED
    is ClockifyError.RateLimited -> CompanionSignInErrorCode.RATE_LIMITED
    is ClockifyError.Offline -> CompanionSignInErrorCode.OFFLINE
    is ClockifyError.Http -> CompanionSignInErrorCode.HTTP_ERROR
    is ClockifyError.ParseError -> CompanionSignInErrorCode.PARSE_ERROR
    is ClockifyError.NoWorkspaceFound -> CompanionSignInErrorCode.NO_WORKSPACE
    is ClockifyError.NotSignedIn -> CompanionSignInErrorCode.NOT_SIGNED_IN
  }
