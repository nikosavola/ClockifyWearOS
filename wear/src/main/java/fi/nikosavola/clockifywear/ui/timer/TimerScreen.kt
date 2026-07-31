package fi.nikosavola.clockifywear.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyError
import fi.nikosavola.clockifywear.ui.errorMessage
import fi.nikosavola.clockifywear.ui.requiresSignIn

@Composable
fun TimerScreen(viewModel: TimerViewModel, onNavigateToSettings: () -> Unit) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current

  // repeatOnLifecycle cancels onForeground/runElapsedTicker together once this screen leaves
  // STARTED, so the 1 s tick never runs while backgrounded or off-screen (PLANNING.md).
  LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      viewModel.onForeground()
      viewModel.runElapsedTicker()
    }
  }

  val scrollState = rememberScrollState()
  ScreenScaffold(scrollState = scrollState) { contentPadding ->
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(contentPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      when (val state = uiState) {
        is TimerUiState.Loading -> {
          Text(text = stringResource(R.string.loading))
        }
        is TimerUiState.Idle -> {
          IdleContent(state = state, onStart = viewModel::start)
        }
        is TimerUiState.Running -> {
          RunningContent(state = state, onStop = viewModel::stop)
        }
        is TimerUiState.Error -> {
          ErrorContent(
            error = state.error,
            onRetry = viewModel::retry,
            onGoToSettings = onNavigateToSettings,
          )
        }
      }
    }
  }
}

@Composable
private fun IdleContent(state: TimerUiState.Idle, onStart: () -> Unit) {
  if (state.hasDefaultProject) {
    Button(onClick = onStart) { Text(text = stringResource(R.string.timer_start_button)) }
  } else {
    Text(text = stringResource(R.string.timer_no_default_project))
  }
}

@Composable
private fun RunningContent(state: TimerUiState.Running, onStop: () -> Unit) {
  // Project name resolution needs the project cache lookup added in M3; showing the raw id (or a
  // fallback label when the entry has none) is enough for M2's start/stop loop.
  Text(text = state.projectId ?: stringResource(R.string.timer_no_project_label))
  Text(text = formatElapsed(state.elapsedSeconds))
  Button(onClick = onStop) { Text(text = stringResource(R.string.timer_stop_button)) }
}

@Composable
private fun ErrorContent(error: ClockifyError, onRetry: () -> Unit, onGoToSettings: () -> Unit) {
  Text(text = errorMessage(error))
  if (requiresSignIn(error)) {
    Button(onClick = onGoToSettings) {
      Text(text = stringResource(R.string.timer_go_to_settings_button))
    }
  } else {
    Button(onClick = onRetry) { Text(text = stringResource(R.string.timer_retry_button)) }
  }
}
