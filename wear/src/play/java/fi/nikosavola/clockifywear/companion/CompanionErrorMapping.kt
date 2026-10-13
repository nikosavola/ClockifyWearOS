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
    // Deliberately the wire-compat fallback rather than a code of its own: this failure is about
    // the watch's own keystore, the phone can do nothing about it beyond retrying, and giving it a
    // code would mean a protocol enum addition plus a matching phone-side message. The precise
    // message is the watch's own (see ClockifyErrorMessages), which is where this is actionable.
    is ClockifyError.KeyNotStored -> CompanionSignInErrorCode.UNKNOWN
  }
