package fi.nikosavola.clockifywear.ui.timer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.ui.ErrorContent
import fi.nikosavola.clockifywear.ui.projects.ProjectColorDot
import kotlin.math.cos
import kotlin.math.sin

private val PROJECT_LABEL_ROW_GAP = 4.dp
private val ICON_BUTTON_ROW_GAP = 12.dp

// material-icons-core is not on this module's classpath (confirmed via
// `./gradlew :wear:dependencies`, contrary to what was assumed going into this change) and
// material-icons-extended is deliberately not worth adding for a handful of glyphs in an app that
// otherwise draws everything as vectors, so every glyph below (Play/Pause/List/Refresh/Clock/
// Settings) is hand-drawn.
private val ICON_GLYPH_SIZE = 24.dp

// The Idle screen's choose-project/recent buttons use IconButtonDefaults.LargeButtonSize (60.dp)
// instead of the implicit default (52.dp) so they read closer to the Play EdgeButton below them;
// this glyph size is IconButtonDefaults.LargeIconSize, the matching icon size for that button size
// (see IconButtonDefaults.iconSizeFor). Kept separate from ICON_GLYPH_SIZE, which the EdgeButton's
// Play/Pause glyphs still use unchanged, since ICON_GLYPH_SIZE is shared via GlyphIcon.
private val IDLE_ICON_BUTTON_SIZE = 60.dp
private val IDLE_ICON_GLYPH_SIZE = 32.dp
private const val PLAY_TRIANGLE_INSET_FRACTION = 0.28f
private val PAUSE_BAR_WIDTH = 6.dp
private val PAUSE_BAR_GAP = 5.dp
private val PAUSE_BAR_CORNER = 1.5.dp
private val LIST_BAR_HEIGHT = 3.dp
// Thicker than a typical 24dp glyph would need, and with a blunter arrowhead (shorter, wider)
// than a scaled-down default: at ExtraSmallButtonSize with no tonal fill behind it, a thin
// stroke and a long thin arrowhead both read as a faint smudge rather than a refresh symbol.
private val REFRESH_STROKE_WIDTH = 3.5.dp
private const val REFRESH_START_ANGLE_DEGREES = -50f
private const val REFRESH_SWEEP_ANGLE_DEGREES = 260f
private const val REFRESH_ARROW_LENGTH_FACTOR = 1.4f
private const val REFRESH_ARROW_WIDTH_FACTOR = 1.5f
private const val REFRESH_ARROW_BACK_FACTOR = 0.2f
private const val REFRESH_SPIN_DURATION_MS = 900
private val CLOCK_STROKE_WIDTH = 2.dp
private val CLOCK_HAND_STROKE_WIDTH = 2.5.dp
private const val CLOCK_HOUR_HAND_LENGTH_FACTOR = 0.35f
private const val CLOCK_MINUTE_HAND_LENGTH_FACTOR = 0.55f
// Angles measured clockwise from 12 o'clock; -60/+60 puts both hands up and out, the classic
// 10:10-ish pose that reads as "clock" rather than an arbitrary time.
private const val CLOCK_HOUR_HAND_ANGLE_DEGREES = -60f
private const val CLOCK_MINUTE_HAND_ANGLE_DEGREES = 60f
private val TOP_BUTTON_ROW_GAP = 8.dp
private val GEAR_STROKE_WIDTH = 2.5.dp
private const val GEAR_TOOTH_COUNT = 8
// Kept short relative to the ring radius so the shape reads as a cog's annulus with square teeth
// rather than a sunburst of spokes at this icon's small on-screen size.
private const val GEAR_TOOTH_LENGTH_FACTOR = 0.35f

@Composable
fun TimerScreen(
  viewModel: TimerViewModel,
  onNavigateToSettings: () -> Unit,
  onNavigateToProjectPicker: () -> Unit,
  onNavigateToRecents: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current

  // repeatOnLifecycle cancels onForeground/runElapsedTicker together once this screen leaves
  // STARTED, so the 1 s tick never runs while backgrounded or off-screen (PLANNING.md).
  LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      viewModel.onForeground()
      viewModel.runElapsedTicker()
    }
  }

  val listState = rememberTransformingLazyColumnState()
  val state = uiState
  ScreenScaffold(
    scrollState = listState,
    // Only Idle and Running anchor an action to the bezel; Loading/Error fall through to the
    // default (no edge button).
    edgeButton = {
      TimerEdgeButton(
        state = state,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        onChooseProject = onNavigateToProjectPicker,
      )
    },
  ) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      // The content is a single fixed block that never fills the viewport, so centering it here
      // (rather than on the item, which wrap-heights and makes Arrangement.Center a no-op)
      // matches the old fillMaxSize+Center Column this screen used before the EdgeButton rework.
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item {
        TimerContent(
          state = state,
          isRefreshing = isRefreshing,
          onChooseProject = onNavigateToProjectPicker,
          onRecent = onNavigateToRecents,
          onRetry = viewModel::retry,
          onRefresh = viewModel::retry,
          onGoToSettings = onNavigateToSettings,
        )
      }
    }
  }
}

@Composable
private fun TimerEdgeButton(
  state: TimerUiState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onChooseProject: () -> Unit,
) {
  when (state) {
    is TimerUiState.Idle ->
      EdgeButton(onClick = if (state.hasDefaultProject) onStart else onChooseProject) {
        PlayIcon(contentDescription = stringResource(R.string.timer_start_button))
      }
    is TimerUiState.Running ->
      EdgeButton(onClick = onStop) {
        PauseIcon(contentDescription = stringResource(R.string.timer_stop_button))
      }
    else -> {}
  }
}

@Composable
private fun TimerContent(
  state: TimerUiState,
  isRefreshing: Boolean,
  onChooseProject: () -> Unit,
  onRecent: () -> Unit,
  onRetry: () -> Unit,
  onRefresh: () -> Unit,
  onGoToSettings: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    when (state) {
      is TimerUiState.Loading -> Text(text = stringResource(R.string.loading))
      is TimerUiState.Idle ->
        IdleContent(
          isRefreshing = isRefreshing,
          onChooseProject = onChooseProject,
          onRecent = onRecent,
          onRefresh = onRefresh,
          onGoToSettings = onGoToSettings,
        )
      is TimerUiState.Running ->
        RunningContent(
          state = state,
          isRefreshing = isRefreshing,
          onRefresh = onRefresh,
          onGoToSettings = onGoToSettings,
        )
      is TimerUiState.Error ->
        ErrorContent(error = state.error, onRetry = onRetry, onGoToSettings = onGoToSettings)
    }
  }
}

// Unlike SettingsButton, deliberately plain (no tonal fill) and sized down to
// ExtraSmallButtonSize: this is the most frequently tapped secondary action on the screen, so it
// reads as a lightweight, low-emphasis affordance rather than a peer of Choose-Project/Recent.
@Composable
private fun RefreshButton(onClick: () -> Unit, isRefreshing: Boolean) {
  // The transition only exists while refreshing, not merely animating toward a resting angle:
  // an always-running rememberInfiniteTransition never lets Compose reach an idle frame, which
  // would hang anything that waits for idle (tests, but also just wasted recomposition on-device).
  val rotationDegrees =
    if (isRefreshing) {
      val transition = rememberInfiniteTransition(label = "refreshSpin")
      val angle by
        transition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = REFRESH_SPIN_DURATION_MS, easing = LinearEasing)
            ),
          label = "refreshRotation",
        )
      angle
    } else {
      0f
    }
  IconButton(
    onClick = onClick,
    modifier = Modifier.size(IconButtonDefaults.ExtraSmallButtonSize),
    colors = IconButtonDefaults.iconButtonColors(),
  ) {
    RefreshIcon(
      contentDescription = stringResource(R.string.timer_refresh_button),
      modifier =
        Modifier.size(IconButtonDefaults.iconSizeFor(IconButtonDefaults.ExtraSmallButtonSize))
          .rotate(rotationDegrees),
    )
  }
}

// Same small-button sizing as RefreshButton used to have, sitting next to it rather than behind a
// new route: TimerScreen already receives onNavigateToSettings for the Error state, so this just
// adds a second call site for the callback it already has.
@Composable
private fun SettingsButton(onClick: () -> Unit) {
  FilledTonalIconButton(
    onClick = onClick,
    modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
  ) {
    SettingsIcon(
      contentDescription = stringResource(R.string.timer_settings_button),
      modifier = Modifier.size(IconButtonDefaults.SmallIconSize),
    )
  }
}

// Shared by Idle/Running: the Refresh and Settings entry points, side by side above the content.
// Center-aligned vertically (not the Row default of Top) because RefreshButton and SettingsButton
// are no longer the same height: RefreshButton's ExtraSmallButtonSize container is shorter than
// SettingsButton's SmallButtonSize one, and top alignment made the smaller one look like it was
// sinking rather than sitting beside its neighbor.
@Composable
private fun TopButtonRow(onRefresh: () -> Unit, onGoToSettings: () -> Unit, isRefreshing: Boolean) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(ICON_BUTTON_ROW_GAP),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RefreshButton(onClick = onRefresh, isRefreshing = isRefreshing)
    SettingsButton(onClick = onGoToSettings)
  }
  Spacer(modifier = Modifier.height(TOP_BUTTON_ROW_GAP))
}

@Composable
private fun IdleContent(
  isRefreshing: Boolean,
  onChooseProject: () -> Unit,
  onRecent: () -> Unit,
  onRefresh: () -> Unit,
  onGoToSettings: () -> Unit,
) {
  TopButtonRow(onRefresh = onRefresh, onGoToSettings = onGoToSettings, isRefreshing = isRefreshing)
  Row(horizontalArrangement = Arrangement.spacedBy(ICON_BUTTON_ROW_GAP)) {
    FilledTonalIconButton(
      onClick = onChooseProject,
      modifier = Modifier.size(IDLE_ICON_BUTTON_SIZE),
    ) {
      ListIcon(
        contentDescription = stringResource(R.string.timer_choose_project_button),
        modifier = Modifier.size(IDLE_ICON_GLYPH_SIZE),
      )
    }
    FilledTonalIconButton(onClick = onRecent, modifier = Modifier.size(IDLE_ICON_BUTTON_SIZE)) {
      ClockIcon(
        contentDescription = stringResource(R.string.timer_recent_button),
        modifier = Modifier.size(IDLE_ICON_GLYPH_SIZE),
      )
    }
  }
}

@Composable
private fun RunningContent(
  state: TimerUiState.Running,
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
  onGoToSettings: () -> Unit,
) {
  TopButtonRow(onRefresh = onRefresh, onGoToSettings = onGoToSettings, isRefreshing = isRefreshing)
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
  // numeralLarge is the single most important value on this screen, so it gets the biggest type
  // on it. It is not actually tabular in the rendered typeface, though: measured on-device, the
  // digit-glyph run visibly changes width tick to tick (e.g. a "1" versus an "8"), so the whole
  // block would jump every second without "tnum" forcing fixed-width digits. Centering on
  // fillMaxWidth rather than relying on the Column's own CenterHorizontally is deliberate: the
  // fillMaxWidth box is measured as exactly screen-centered (confirmed via uiautomator bounds), but
  // "tnum" alone only equalizes each digit's advance width, not where its ink sits within that
  // width, so a trailing "1" still looked visibly off-center. FontFamily.Monospace draws each digit
  // centered in its cell, which fixed that residual asymmetry (measured ink-center offset dropped
  // from ~7-8px to ~1px, within antialiasing noise, across several digit combinations).
  Text(
    text = formatElapsed(state.elapsedSeconds),
    modifier = Modifier.fillMaxWidth(),
    style =
      MaterialTheme.typography.numeralLarge.copy(
        fontFeatureSettings = "tnum",
        fontFamily = FontFamily.Monospace,
      ),
    textAlign = TextAlign.Center,
  )
  if (!state.description.isNullOrBlank()) {
    Text(
      text = state.description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// Shared scaffolding for the hand-drawn glyphs: fixed size, current content color, a11y label.
@Composable
private fun GlyphIcon(
  contentDescription: String,
  modifier: Modifier = Modifier,
  draw: DrawScope.(color: Color) -> Unit,
) {
  val color = LocalContentColor.current
  Canvas(
    modifier =
      modifier.size(ICON_GLYPH_SIZE).semantics { this.contentDescription = contentDescription }
  ) {
    draw(color)
  }
}

@Composable
private fun PlayIcon(contentDescription: String, modifier: Modifier = Modifier) {
  GlyphIcon(contentDescription, modifier) { color ->
    val path =
      Path().apply {
        moveTo(size.width * PLAY_TRIANGLE_INSET_FRACTION, 0f)
        lineTo(size.width * PLAY_TRIANGLE_INSET_FRACTION, size.height)
        lineTo(size.width * (1f - PLAY_TRIANGLE_INSET_FRACTION), size.height / 2f)
        close()
      }
    drawPath(path, color = color)
  }
}

@Composable
private fun PauseIcon(contentDescription: String, modifier: Modifier = Modifier) {
  GlyphIcon(contentDescription, modifier) { color ->
    val barWidth = PAUSE_BAR_WIDTH.toPx()
    val gap = PAUSE_BAR_GAP.toPx()
    val corner = CornerRadius(PAUSE_BAR_CORNER.toPx())
    val totalWidth = barWidth * 2 + gap
    val startX = (size.width - totalWidth) / 2
    drawRoundRect(
      color = color,
      topLeft = Offset(startX, 0f),
      size = Size(barWidth, size.height),
      cornerRadius = corner,
    )
    drawRoundRect(
      color = color,
      topLeft = Offset(startX + barWidth + gap, 0f),
      size = Size(barWidth, size.height),
      cornerRadius = corner,
    )
  }
}

@Composable
private fun ListIcon(contentDescription: String, modifier: Modifier = Modifier) {
  GlyphIcon(contentDescription, modifier) { color ->
    val barHeight = LIST_BAR_HEIGHT.toPx()
    val rowCount = 3
    val gap = (size.height - barHeight * rowCount) / (rowCount - 1)
    for (row in 0 until rowCount) {
      val y = row * (barHeight + gap)
      drawRoundRect(
        color = color,
        topLeft = Offset(0f, y),
        size = Size(size.width, barHeight),
        cornerRadius = CornerRadius(barHeight / 2f),
      )
    }
  }
}

@Composable
private fun RefreshIcon(contentDescription: String, modifier: Modifier = Modifier) {
  GlyphIcon(contentDescription, modifier) { color ->
    val strokeWidth = REFRESH_STROKE_WIDTH.toPx()
    val radius = (minOf(size.width, size.height) - strokeWidth) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawArc(
      color = color,
      startAngle = REFRESH_START_ANGLE_DEGREES,
      sweepAngle = REFRESH_SWEEP_ANGLE_DEGREES,
      useCenter = false,
      topLeft = Offset(center.x - radius, center.y - radius),
      size = Size(radius * 2f, radius * 2f),
      style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    // Arrowhead at the open end of the arc, oriented along its tangent, so the arc reads as a
    // directional "refresh" sweep rather than a plain incomplete circle.
    val endAngleRadians =
      Math.toRadians((REFRESH_START_ANGLE_DEGREES + REFRESH_SWEEP_ANGLE_DEGREES).toDouble())
    val tangent = Offset(-sin(endAngleRadians).toFloat(), cos(endAngleRadians).toFloat())
    val normal = Offset(-tangent.y, tangent.x)
    val endPoint =
      center +
        Offset(radius * cos(endAngleRadians).toFloat(), radius * sin(endAngleRadians).toFloat())
    val arrowLength = strokeWidth * REFRESH_ARROW_LENGTH_FACTOR
    val arrowWidth = strokeWidth * REFRESH_ARROW_WIDTH_FACTOR
    val tip = endPoint + tangent * arrowLength
    val back = endPoint - tangent * (arrowLength * REFRESH_ARROW_BACK_FACTOR)
    val arrow =
      Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + normal.x * arrowWidth, back.y + normal.y * arrowWidth)
        lineTo(back.x - normal.x * arrowWidth, back.y - normal.y * arrowWidth)
        close()
      }
    drawPath(arrow, color = color)
  }
}

@Composable
private fun ClockIcon(contentDescription: String, modifier: Modifier = Modifier) {
  GlyphIcon(contentDescription, modifier) { color ->
    val strokeWidth = CLOCK_STROKE_WIDTH.toPx()
    val radius = (minOf(size.width, size.height) - strokeWidth) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
    val handStrokeWidth = CLOCK_HAND_STROKE_WIDTH.toPx()
    drawClockHand(
      center = center,
      length = radius * CLOCK_HOUR_HAND_LENGTH_FACTOR,
      angleDegrees = CLOCK_HOUR_HAND_ANGLE_DEGREES,
      color = color,
      strokeWidth = handStrokeWidth,
    )
    drawClockHand(
      center = center,
      length = radius * CLOCK_MINUTE_HAND_LENGTH_FACTOR,
      angleDegrees = CLOCK_MINUTE_HAND_ANGLE_DEGREES,
      color = color,
      strokeWidth = handStrokeWidth,
    )
  }
}

@Composable
private fun SettingsIcon(contentDescription: String, modifier: Modifier = Modifier) {
  GlyphIcon(contentDescription, modifier) { color ->
    val strokeWidth = GEAR_STROKE_WIDTH.toPx()
    // The tooth tip (plus its round cap overshoot) lands exactly on the icon's edge, same as
    // ClockIcon's ring; the ring itself sits further in, leaving room for the teeth outside it.
    val maxRadius = minOf(size.width, size.height) / 2f - strokeWidth / 2f
    val toothLength = maxRadius * GEAR_TOOTH_LENGTH_FACTOR
    val ringRadius = maxRadius - toothLength
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(
      color = color,
      radius = ringRadius,
      center = center,
      style = Stroke(width = strokeWidth),
    )
    for (tooth in 0 until GEAR_TOOTH_COUNT) {
      val angleRadians = Math.toRadians(tooth * 360.0 / GEAR_TOOTH_COUNT)
      val direction = Offset(cos(angleRadians).toFloat(), sin(angleRadians).toFloat())
      drawLine(
        color = color,
        start = center + direction * ringRadius,
        end = center + direction * maxRadius,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
      )
    }
  }
}

private fun DrawScope.drawClockHand(
  center: Offset,
  length: Float,
  angleDegrees: Float,
  color: Color,
  strokeWidth: Float,
) {
  val angleRadians = Math.toRadians(angleDegrees.toDouble())
  val end =
    Offset(
      center.x + length * sin(angleRadians).toFloat(),
      center.y - length * cos(angleRadians).toFloat(),
    )
  drawLine(
    color = color,
    start = center,
    end = end,
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
}
