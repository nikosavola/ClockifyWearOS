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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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
  val transformationSpec = rememberTransformationSpec()
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
              ProjectRow(
                project = project,
                onClick = { onProjectSelected(project.id) },
                // Needs the item scope's implicit receiver (`this`), so it can't be resolved
                // inside ProjectRow itself; the item scope only exists here in the items{} lambda.
                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                transformation = SurfaceTransformation(transformationSpec),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProjectRow(
  project: ProjectDto,
  onClick: () -> Unit,
  modifier: Modifier,
  transformation: SurfaceTransformation,
) {
  PickerRow(
    title = project.name,
    color = parseProjectColor(project.color),
    onClick = onClick,
    modifier = modifier,
    transformation = transformation,
  )
}
