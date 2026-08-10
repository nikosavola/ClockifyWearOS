package fi.nikosavola.clockifywear.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
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
      item { Text(text = stringResource(R.string.project_picker_title)) }
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

private val PROJECT_COLOR_DOT_SIZE = 12.dp
private val PROJECT_ROW_GAP = 8.dp

@Composable
private fun ProjectRow(project: ProjectDto, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val dotColor = parseProjectColor(project.color) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
      modifier =
        Modifier.size(PROJECT_COLOR_DOT_SIZE).background(color = dotColor, shape = CircleShape)
    )
    Spacer(modifier = Modifier.width(PROJECT_ROW_GAP))
    Text(text = project.name)
  }
}
