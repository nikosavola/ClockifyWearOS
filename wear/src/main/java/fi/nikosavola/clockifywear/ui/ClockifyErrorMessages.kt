package fi.nikosavola.clockifywear.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyError

/** Maps a repository failure to the string resource shown in an error state. */
@StringRes
fun errorMessageRes(error: ClockifyError): Int =
  when (error) {
    is ClockifyError.Unauthorized -> R.string.error_unauthorized
    is ClockifyError.RateLimited -> R.string.error_rate_limited
    is ClockifyError.Offline -> R.string.error_offline
    is ClockifyError.Http -> R.string.error_http
    is ClockifyError.ParseError -> R.string.error_parse
    is ClockifyError.NoWorkspaceFound -> R.string.error_no_workspace
    is ClockifyError.NotSignedIn -> R.string.error_not_signed_in
  }

/**
 * True when [error] means the stored identity is missing or rejected, so the only useful action is
 * routing to Settings rather than offering a retry of the same request.
 */
fun requiresSignIn(error: ClockifyError): Boolean =
  error is ClockifyError.Unauthorized || error is ClockifyError.NotSignedIn

/** [ClockifyError.Http] is the one variant whose message carries a format argument. */
@Composable
fun errorMessage(error: ClockifyError): String =
  if (error is ClockifyError.Http) {
    stringResource(errorMessageRes(error), error.code)
  } else {
    stringResource(errorMessageRes(error))
  }
