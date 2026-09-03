package fi.nikosavola.clockifywear.companion

/**
 * Sign-in failure reasons sent as a [signInFailurePath] message payload (via [name]), instead of a
 * display string, because the watch has no access to the phone app's string resources. A shared
 * enum instead of a bare wire-format string: adding a case forces an exhaustive `when` on both
 * sides (the wear mapping from `ClockifyError`, and the phone mapping to a string resource) to be
 * updated together, which is exactly the drift a hand-written string contract can't catch.
 */
enum class CompanionSignInErrorCode {
  UNAUTHORIZED,
  RATE_LIMITED,
  OFFLINE,
  HTTP_ERROR,
  PARSE_ERROR,
  NO_WORKSPACE,
  NOT_SIGNED_IN,
  /** The watch already has a valid session; a companion request never overwrites one silently. */
  ALREADY_SIGNED_IN,
  /** Wire-compat fallback: a code this build doesn't recognize, not a real absence of a reason. */
  UNKNOWN;

  companion object {
    fun fromWireValue(value: String): CompanionSignInErrorCode =
      entries.find { it.name == value } ?: UNKNOWN
  }
}
