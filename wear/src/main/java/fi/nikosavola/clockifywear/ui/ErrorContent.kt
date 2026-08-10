package fi.nikosavola.clockifywear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyError

/**
 * Shared error display for every screen: the message plus either a retry button, or a
 * go-to-Settings button when [ClockifyError] means the stored identity is missing or rejected.
 */
@Composable
fun ErrorContent(error: ClockifyError, onRetry: () -> Unit, onGoToSettings: () -> Unit) {
  Text(text = errorMessage(error))
  if (requiresSignIn(error)) {
    Button(onClick = onGoToSettings) {
      Text(text = stringResource(R.string.timer_go_to_settings_button))
    }
  } else {
    Button(onClick = onRetry) { Text(text = stringResource(R.string.timer_retry_button)) }
  }
}
