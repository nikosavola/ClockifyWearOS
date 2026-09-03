package fi.nikosavola.clockifywear.mobile

import androidx.annotation.StringRes
import fi.nikosavola.clockifywear.companion.CompanionSignInErrorCode

/**
 * Maps a [SignInUiState.Failure.errorCode] to the string resource shown in the UI. Exhaustive over
 * [CompanionSignInErrorCode] (no `else`) so a new code added to the shared enum fails to compile
 * here until it's given a message, instead of silently falling through to a generic one.
 */
@StringRes
fun errorMessageRes(errorCode: CompanionSignInErrorCode): Int =
  when (errorCode) {
    CompanionSignInErrorCode.UNAUTHORIZED -> R.string.sign_in_error_unauthorized
    CompanionSignInErrorCode.RATE_LIMITED -> R.string.sign_in_error_rate_limited
    CompanionSignInErrorCode.OFFLINE -> R.string.sign_in_error_offline
    CompanionSignInErrorCode.HTTP_ERROR -> R.string.sign_in_error_http
    CompanionSignInErrorCode.PARSE_ERROR -> R.string.sign_in_error_parse
    CompanionSignInErrorCode.NO_WORKSPACE -> R.string.sign_in_error_no_workspace
    CompanionSignInErrorCode.NOT_SIGNED_IN -> R.string.sign_in_error_unknown
    CompanionSignInErrorCode.ALREADY_SIGNED_IN -> R.string.sign_in_error_already_signed_in
    CompanionSignInErrorCode.UNKNOWN -> R.string.sign_in_error_unknown
  }
