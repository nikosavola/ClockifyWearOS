package fi.nikosavola.clockifywear.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.ErrorContent
import fi.nikosavola.clockifywear.ui.projects.ProjectColorDot

private val PROJECT_LABEL_ROW_GAP = 4.dp

@Composable
fun TimerScreen(
  viewModel: TimerViewModel,
  onNavigateToSettings: () -> Unit,
  onNavigateToProjectPicker: () -> Unit,
  onNavigateToRecents: () -> Unit,
) {
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
          IdleContent(
            state = state,
            onStart = viewModel::start,
            onChooseProject = onNavigateToProjectPicker,
            onRecent = onNavigateToRecents,
          )
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
private fun IdleContent(
  state: TimerUiState.Idle,
  onStart: () -> Unit,
  onChooseProject: () -> Unit,
  onRecent: () -> Unit,
) {
  if (state.hasDefaultProject) {
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
      Text(text = stringResource(R.string.timer_start_button))
    }
    // De-emphasized: switching the default project is secondary to starting the timer.
    FilledTonalButton(onClick = onChooseProject, modifier = Modifier.fillMaxWidth()) {
      Text(text = stringResource(R.string.timer_choose_project_button))
    }
  } else {
    Button(onClick = onChooseProject, modifier = Modifier.fillMaxWidth()) {
      Text(text = stringResource(R.string.timer_choose_project_button))
    }
  }
  // Unlike "Choose project", restarting a recent entry is always a useful secondary action,
  // whether or not a default project is set.
  FilledTonalButton(onClick = onRecent, modifier = Modifier.fillMaxWidth()) {
    Text(text = stringResource(R.string.timer_recent_button))
  }
}

@Composable
private fun RunningContent(state: TimerUiState.Running, onStop: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    ProjectColorDot(color = state.projectColor)
    Spacer(modifier = Modifier.width(PROJECT_LABEL_ROW_GAP))
    Text(
      text =
        state.projectName ?: state.projectId ?: stringResource(R.string.timer_no_project_label),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  // numeralLarge is the dedicated tabular-figure token for a large numeric readout; it is the
  // single most important value on this screen, so it gets the biggest type on it.
  Text(text = formatElapsed(state.elapsedSeconds), style = MaterialTheme.typography.numeralLarge)
  Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
    Text(text = stringResource(R.string.timer_stop_button))
  }
}
