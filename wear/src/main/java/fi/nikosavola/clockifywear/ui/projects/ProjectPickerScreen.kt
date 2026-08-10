package fi.nikosavola.clockifywear.ui.projects

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
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.ui.ErrorContent

@Composable
fun ProjectPickerScreen(
  viewModel: ProjectPickerViewModel,
  onProjectSelected: (projectId: String) -> Unit,
  onNavigateToSettings: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) { viewModel.load() }

  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
      item { ListHeader { Text(text = stringResource(R.string.project_picker_title)) } }
      when (val state = uiState) {
        is ProjectPickerUiState.Loading -> {
          item { Text(text = stringResource(R.string.loading)) }
        }
        is ProjectPickerUiState.Error -> {
          item {
            ErrorContent(
              error = state.error,
              onRetry = viewModel::load,
              onGoToSettings = onNavigateToSettings,
            )
          }
        }
        is ProjectPickerUiState.Loaded -> {
          if (state.projects.isEmpty()) {
            item { Text(text = stringResource(R.string.project_picker_empty)) }
          } else {
            items(items = state.projects, key = { it.id }) { project ->
              ProjectRow(project = project, onClick = { onProjectSelected(project.id) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProjectRow(project: ProjectDto, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    icon = { ProjectColorDot(color = parseProjectColor(project.color)) },
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Text(text = project.name)
  }
}
