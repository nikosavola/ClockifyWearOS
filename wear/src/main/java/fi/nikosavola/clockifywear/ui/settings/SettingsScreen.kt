package fi.nikosavola.clockifywear.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.errorMessage

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val scrollState = rememberScrollState()

  ScreenScaffold(scrollState = scrollState) { contentPadding ->
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(contentPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(text = stringResource(R.string.settings_title))
      when (val state = uiState) {
        is SettingsUiState.Loading -> {
          Text(text = stringResource(R.string.loading))
        }
        is SettingsUiState.SigningIn -> {
          Text(text = stringResource(R.string.settings_signing_in))
        }
        is SettingsUiState.SignedIn -> {
          SignedInContent(state = state, onSignOut = viewModel::signOut)
        }
        is SettingsUiState.SignedOut -> {
          SignedOutContent(state = state, onSignIn = viewModel::signIn)
        }
      }
    }
  }
}

@Composable
private fun SignedInContent(state: SettingsUiState.SignedIn, onSignOut: () -> Unit) {
  Text(text = stringResource(R.string.settings_signed_in_workspace, state.workspaceId))
  Button(onClick = onSignOut) { Text(text = stringResource(R.string.settings_sign_out_button)) }
}

@Composable
private fun SignedOutContent(state: SettingsUiState.SignedOut, onSignIn: (String) -> Unit) {
  var apiKeyInput by remember { mutableStateOf("") }
  val clipboardManager = LocalClipboardManager.current

  state.error?.let { error -> Text(text = errorMessage(error)) }
  Text(text = stringResource(R.string.settings_api_key_label))
  // BasicTextField, not a material3 text field: a watch gets the system IME, and Wear OS 3+
  // additionally offers phone remote input automatically (PLANNING.md "SettingsScreen").
  // BasicTextField defaults to black text and a black cursor, which is invisible on the dark Wear
  // theme, and draws no boundary of its own, so an unstyled one looks like empty space. Both have
  // to be supplied explicitly.
  BasicTextField(
    value = apiKeyInput,
    onValueChange = { apiKeyInput = it },
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 8.dp)
        .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
        .padding(8.dp),
    singleLine = true,
    textStyle =
      MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
  )
  // A watch's Wireless debugging pairing already implies a paired phone, and Wear OS syncs the
  // system clipboard between them, so pasting a key copied on the phone works without any
  // Data Layer code. Long-press-to-paste on BasicTextField is not reliably discoverable on a
  // small round screen, so this button reads the clipboard directly as a visible alternative.
  Text(text = stringResource(R.string.settings_clipboard_hint))
  TextButton(onClick = { clipboardManager.getText()?.let { apiKeyInput = it.text } }) {
    Text(text = stringResource(R.string.settings_paste_button))
  }
  Button(onClick = { onSignIn(apiKeyInput) }, enabled = apiKeyInput.isNotBlank()) {
    Text(text = stringResource(R.string.settings_sign_in_button))
  }
}
