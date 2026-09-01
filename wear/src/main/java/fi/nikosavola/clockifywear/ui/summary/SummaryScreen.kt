package fi.nikosavola.clockifywear.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.ErrorContent
import fi.nikosavola.clockifywear.ui.projects.ProjectColorDot
import fi.nikosavola.clockifywear.ui.timer.elapsedHoursAndMinutes

private val SUMMARY_ROW_GAP = 8.dp
// Tighter than the list's usual between-item gap: groups each section's entries into one visually
// contiguous block (an M3-recommended "grouped card list" reading, closer to how a single Card
// with several rows would look) rather than reading as separate, unrelated rows.
private val SUMMARY_ITEM_SPACING = 4.dp

@Composable
fun SummaryScreen(viewModel: SummaryViewModel, onNavigateToSettings: () -> Unit) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Reloads on every resume, not just once per composition: this screen is a page of the
  // persistent root pager (see NavGraph.kt), so it never leaves the composition just from
  // backgrounding the app - a plain LaunchedEffect(viewModel) would leave day/week boundaries and
  // any in-progress entry's duration frozen at whatever they were when the app was last opened.
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.load() }
  }

  val listState = rememberTransformingLazyColumnState()
  val transformationSpec = rememberTransformationSpec()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(SUMMARY_ITEM_SPACING),
      // The bezel (physical or virtual) scrolls this list, not the main pager - see NavGraph.kt,
      // which deliberately leaves the pager itself without rotary input for that reason.
      rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
    ) {
      item { ListHeader { Text(text = stringResource(R.string.summary_title)) } }
      when (val state = uiState) {
        is SummaryUiState.Loading -> {
          item { Text(text = stringResource(R.string.loading)) }
        }
        is SummaryUiState.Error -> {
          item {
            ErrorContent(
              error = state.error,
              onRetry = viewModel::load,
              onGoToSettings = onNavigateToSettings,
            )
          }
        }
        is SummaryUiState.Loaded -> {
          summarySection(R.string.summary_section_today, state.today, transformationSpec)
          summarySection(R.string.summary_section_this_week, state.thisWeek, transformationSpec)
          summarySection(R.string.summary_section_last_week, state.lastWeek, transformationSpec)
        }
      }
    }
  }
}

// Split out of SummaryScreen itself just to keep that composable under detekt's LongMethod and
// CognitiveComplexMethod thresholds - Today/This week/Last week each render identically.
private fun TransformingLazyColumnScope.summarySection(
  titleRes: Int,
  section: SummarySection,
  transformationSpec: TransformationSpec,
) {
  item {
    SectionHeader(
      titleRes = titleRes,
      totalSeconds = section.totalSeconds,
      modifier = Modifier.transformedHeight(this, transformationSpec),
      transformation = SurfaceTransformation(transformationSpec),
    )
  }
  if (section.entries.isEmpty()) {
    item { Text(text = stringResource(R.string.summary_section_empty)) }
  } else {
    items(items = section.entries, key = { it.id }) { entry ->
      SummaryRow(
        entry = entry,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
      )
    }
  }
}

@Composable
private fun SectionHeader(
  titleRes: Int,
  totalSeconds: Long,
  modifier: Modifier = Modifier,
  transformation: SurfaceTransformation? = null,
) {
  ListHeader(modifier = modifier, transformation = transformation) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = stringResource(titleRes))
      Text(text = formatDurationHoursMinutes(totalSeconds))
    }
  }
}

// A Card, not a bare Row: on a round screen, a Row's own padding cannot compensate for content
// drawn near the curved edge (verified on-device - a plain Row with generous padding still clipped
// trailing duration text at some scroll positions). Card's transformation param carries the same
// round-safe content inset PickerRow gets for free from Button - see PickerRow's own comment.
// CardDefaults' own colors (not overridden) give each row the M3-recommended tonal container, so
// entries read as a grouped block rather than plain text floating on the screen background.
@Composable
private fun SummaryRow(
  entry: SummaryEntryDisplay,
  modifier: Modifier = Modifier,
  transformation: SurfaceTransformation? = null,
) {
  Card(modifier = modifier, transformation = transformation) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(SUMMARY_ROW_GAP),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ProjectColorDot(color = entry.projectColor)
      Text(
        text = entry.projectName ?: stringResource(R.string.timer_no_project_label),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
      )
      Text(
        text = formatDurationHoursMinutes(entry.durationSeconds),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// Reuses the Tile's own duration strings (named tile_* for historical reasons - see
// ClockifyTileService for the identical hours/minutes selection logic) rather than duplicating a
// second, differently-named copy of the same three format strings across every locale.
@Composable
private fun formatDurationHoursMinutes(totalSeconds: Long): String {
  val (hours, minutes) = elapsedHoursAndMinutes(totalSeconds)
  return when {
    hours == 0L -> stringResource(R.string.tile_duration_minutes_only, minutes)
    minutes == 0L -> stringResource(R.string.tile_duration_hours_only, hours)
    else -> stringResource(R.string.tile_duration_hours_minutes, hours, minutes)
  }
}
