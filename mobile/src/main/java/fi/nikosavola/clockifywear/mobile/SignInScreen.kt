package fi.nikosavola.clockifywear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val SCREEN_PADDING = 24.dp
private val CONTENT_GAP = 16.dp

@Composable
fun SignInScreen(viewModel: SignInViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
  val clipboard = LocalClipboard.current
  val coroutineScope = rememberCoroutineScope()

  Scaffold { contentPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(contentPadding)
          .padding(SCREEN_PADDING),
      verticalArrangement = Arrangement.spacedBy(CONTENT_GAP),
    ) {
      Text(
        text = stringResource(R.string.sign_in_title),
        style = MaterialTheme.typography.headlineSmall,
      )
      Text(
        text = stringResource(R.string.sign_in_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedTextField(
        value = apiKeyInput,
        onValueChange = viewModel::updateApiKeyInput,
        label = { Text(stringResource(R.string.sign_in_api_key_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // Password keyboard type: without it, some IMEs treat this as normal text and offer to
        // save/suggest it, same reasoning as the watch's own key field (SettingsScreen.kt).
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
      )

      OutlinedButton(
        onClick = {
          coroutineScope.launch {
            val pastedText =
              clipboard.getClipEntry()?.clipData?.let { clipData ->
                if (clipData.itemCount > 0) clipData.getItemAt(0).text?.toString() else null
              }
            pastedText?.let(viewModel::updateApiKeyInput)
          }
        }
      ) {
        Text(stringResource(R.string.sign_in_paste_button))
      }

      Button(
        onClick = { viewModel.sendApiKey(apiKeyInput.trim()) },
        enabled = apiKeyInput.isNotBlank() && !uiState.isBusy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.sign_in_send_button))
      }

      StatusMessage(uiState)
    }
  }
}

private val SignInUiState.isBusy: Boolean
  get() = this is SignInUiState.Sending || this is SignInUiState.WaitingForWatch

@Composable
private fun StatusMessage(uiState: SignInUiState) {
  when (uiState) {
    is SignInUiState.Idle -> {}
    is SignInUiState.Sending -> {
      Text(stringResource(R.string.sign_in_status_sending))
    }
    is SignInUiState.WaitingForWatch -> {
      Text(stringResource(R.string.sign_in_status_waiting))
    }
    is SignInUiState.Success -> {
      val email = uiState.email
      val text =
        if (email != null) {
          stringResource(R.string.sign_in_status_success, email)
        } else {
          stringResource(R.string.sign_in_status_success_no_email)
        }
      Text(text = text, color = MaterialTheme.colorScheme.primary)
    }
    is SignInUiState.Failure -> {
      Text(
        text = stringResource(errorMessageRes(uiState.errorCode)),
        color = MaterialTheme.colorScheme.error,
      )
    }
    is SignInUiState.Timeout -> {
      Text(
        text = stringResource(R.string.sign_in_status_timeout),
        color = MaterialTheme.colorScheme.error,
      )
    }
    is SignInUiState.NoWatchFound -> {
      Text(
        text = stringResource(R.string.sign_in_status_no_watch),
        color = MaterialTheme.colorScheme.error,
      )
    }
    is SignInUiState.SendFailed -> {
      Text(
        text = stringResource(R.string.sign_in_status_send_failed),
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}
