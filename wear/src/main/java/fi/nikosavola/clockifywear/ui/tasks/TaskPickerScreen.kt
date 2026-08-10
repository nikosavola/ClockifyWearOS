package fi.nikosavola.clockifywear.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.ErrorContent

@Composable
fun TaskPickerScreen(
  viewModel: TaskPickerViewModel,
  onStarted: () -> Unit,
  onNavigateToSettings: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) { viewModel.load() }

  // Started is reached both from an auto-start (empty task list, via load()) and from an explicit
  // selectTask() click; navigating here rather than from either call site keeps it a single path.
  LaunchedEffect(uiState) { if (uiState is TaskPickerUiState.Started) onStarted() }

  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
      when (val state = uiState) {
        is TaskPickerUiState.Loading -> {
          item { Text(text = stringResource(R.string.loading)) }
        }
        is TaskPickerUiState.Started -> {
          item { Text(text = stringResource(R.string.loading)) }
        }
        is TaskPickerUiState.Error -> {
          item {
            ErrorContent(
              error = state.error,
              onRetry = viewModel::load,
              onGoToSettings = onNavigateToSettings,
            )
          }
        }
        is TaskPickerUiState.Loaded -> {
          item { Text(text = state.projectName) }
          item {
            TaskRow(
              name = stringResource(R.string.task_picker_no_task_option),
              onClick = { viewModel.selectTask(null) },
            )
          }
          items(items = state.tasks, key = { it.id }) { task ->
            TaskRow(name = task.name, onClick = { viewModel.selectTask(task.id) })
          }
        }
      }
    }
  }
}

@Composable
private fun TaskRow(name: String, onClick: () -> Unit) {
  Text(text = name, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp))
}
