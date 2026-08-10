package fi.nikosavola.clockifywear.ui.settings

import fi.nikosavola.clockifywear.data.ClockifyError

sealed interface SettingsUiState {
  data object Loading : SettingsUiState

  /** [error] is set only after a rejected sign-in attempt; null on a plain signed-out screen. */
  data class SignedOut(val error: ClockifyError? = null) : SettingsUiState

  data object SigningIn : SettingsUiState

  data class SignedIn(val email: String?, val workspaceId: String) : SettingsUiState
}
