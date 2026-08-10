package fi.nikosavola.clockifywear.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val repository: ClockifyRepository,
  private val settingsStore: SettingsStore,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
  val uiState: StateFlow<SettingsUiState> = mutableUiState.asStateFlow()

  init {
    load()
  }

  /**
   * Returns the launched [Job] so tests can `join()` it instead of racing real DataStore/network
   * I/O against virtual-time advancement (see TimerViewModel.onForeground's doc).
   */
  fun load(): Job = viewModelScope.launch { refresh() }

  fun signIn(apiKey: String): Job = viewModelScope.launch {
    mutableUiState.value = SettingsUiState.SigningIn
    when (val result = repository.signIn(apiKey)) {
      is ClockifyResult.Success -> {
        refresh()
      }
      is ClockifyResult.Failure -> {
        mutableUiState.value = SettingsUiState.SignedOut(error = result.error)
      }
    }
  }

  fun signOut(): Job = viewModelScope.launch {
    settingsStore.clear()
    mutableUiState.value = SettingsUiState.SignedOut()
  }

  private suspend fun refresh() {
    val settings = settingsStore.currentSettings()
    val workspaceId = settings.workspaceId
    mutableUiState.value =
      if (workspaceId != null) {
        SettingsUiState.SignedIn(email = settings.email, workspaceId = workspaceId)
      } else {
        SettingsUiState.SignedOut()
      }
  }
}
