package fi.nikosavola.clockifywear.ui.recents

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.ErrorContent

@Composable
fun RecentsScreen(
  viewModel: RecentsViewModel,
  onStarted: () -> Unit,
  onNavigateToSettings: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) { viewModel.load() }

  // Started is reached from restart(); navigating here rather than from the click handler keeps
  // it a single path, same pattern as TaskPickerScreen.
  LaunchedEffect(uiState) { if (uiState is RecentsUiState.Started) onStarted() }

  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
      item { ListHeader { Text(text = stringResource(R.string.recents_title)) } }
      when (val state = uiState) {
        is RecentsUiState.Loading -> {
          item { Text(text = stringResource(R.string.loading)) }
        }
        is RecentsUiState.Started -> {
          item { Text(text = stringResource(R.string.loading)) }
        }
        is RecentsUiState.Error -> {
          item {
            ErrorContent(
              error = state.error,
              onRetry = viewModel::load,
              onGoToSettings = onNavigateToSettings,
            )
          }
        }
        is RecentsUiState.Loaded -> {
          if (state.entries.isEmpty()) {
            item { Text(text = stringResource(R.string.recents_empty)) }
          } else {
            items(
              items = state.entries,
              key = { "${it.projectId}-${it.taskId}-${it.description}" },
            ) { entry ->
              RecentRow(entry = entry, onClick = { viewModel.restart(entry) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RecentRow(entry: RecentEntryDisplay, onClick: () -> Unit) {
  val description = entry.description
  val secondaryLabel: (@Composable RowScope.() -> Unit)? =
    if (!description.isNullOrBlank()) {
      { Text(text = description) }
    } else {
      null
    }
  Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    secondaryLabel = secondaryLabel,
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Text(text = entry.projectName)
  }
}
