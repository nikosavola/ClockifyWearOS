package fi.nikosavola.clockifywear.ui.timer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val PROJECT_LABEL_ROW_GAP = 4.dp
private val ICON_BUTTON_ROW_GAP = 12.dp
// Extra breathing room between the project-name row and the (independently, always-centered)
// elapsed-time number below it - see the comment on RunningContent's Row for why this is a visual
// offset rather than ordinary layout spacing. Must stay comfortably below TOP_BUTTON_ROW_GAP (8dp):
// this shifts the row up from its normal position, and going much past that gap would run it into
// the refresh button above, which does not move.
private val PROJECT_LABEL_CENTER_GAP = 6.dp

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
// Thinner than a typical 24dp glyph's default stroke would be, to read as a light utility icon
// rather than a bold peer of the other icon buttons on this screen - but not razor-thin, since at
// ExtraSmallButtonSize with no tonal fill behind it a too-thin stroke reads as a faint smudge
// rather than a refresh symbol. The arrowhead scales off this via REFRESH_ARROW_*_FACTOR, so it
// thins along with the arc.
private val REFRESH_STROKE_WIDTH = 2.5.dp
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
// Roughly a quarter of a round watch screen's width: enough that a scroll's incidental horizontal
// wobble never reaches it, but a deliberate swipe comfortably does.
private val SETTINGS_SWIPE_THRESHOLD = 56.dp
// Kept clear of the left edge so this detector never contends with SwipeDismissableNavHost's own
// swipe-to-dismiss recognition, which starts from that same zone (see NavGraph.kt).
private val SETTINGS_SWIPE_EDGE_GUARD = 32.dp
// The glyph itself is well below any named IconButtonDefaults token, since this is a low-emphasis,
// frequently-tapped utility rather than a peer of the other icon buttons on this screen - but the
// tappable container stays at Wear OS's minimum accessible touch target (48dp, see
// https://developer.android.com/training/wearables/accessibility), smaller only visually.
private val REFRESH_BUTTON_SIZE = 48.dp
private val REFRESH_ICON_SIZE = 16.dp
// How far the EdgeButton's Play/Pause glyph shrinks on tap before springing back to full size,
// the visual half of the tap feedback (the other half is the haptic pulse alongside it).
private const val EDGE_BUTTON_PRESS_SCALE = 0.85f

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
  // Settings has no persistent button anymore, reachable only by this swipe, scoped to Idle/Running
  // exactly like TopButtonRow/the EdgeButton (not Error). Attached to this outer Box, not to
  // TimerContent's own Column below: the ElapsedTimeOverlay added alongside it for Running is a
  // sibling that visually sits on top of (part of) that Column at the screen's vertical center, and
  // a plain Text with no gesture handler of its own still wins Compose's hit-test for touches
  // landing on it, silently swallowing them before they would ever reach a sibling's pointerInput
  // underneath - swiping through that band stopped reaching TimerContent's gesture detector
  // entirely once the overlay was introduced. A pointerInput attached directly to this Box, which
  // both of them are inside rather than beside, does not have that problem.
  val swipeModifier = swipeToSettingsModifier(state, onNavigateToSettings)
  // The elapsed-time readout (and, when present, the description below it) are rendered as an
  // overlay on this outer Box rather than inside ScreenScaffold's own content slot below: that
  // slot's coordinate space starts after ScreenScaffold's contentPadding (reserved for the
  // status area above and the EdgeButton below), which are unequal, so centering within it does
  // not land at the screen's actual vertical center - only this Box, sized to the raw screen
  // before any of that padding, can guarantee that regardless of what else is on screen.
  Box(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
    ScreenScaffold(
      scrollState = listState,
      // Only Idle and Running anchor an action to the bezel; Error falls through to the default
      // (no edge button).
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
        verticalArrangement = Arrangement.Top,
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
    if (state is TimerUiState.Running) {
      ElapsedTimeOverlay(state)
    }
  }
}

// See TimerScreen's outer Box comment for why this lives at the screen level, aligned within that
// full-screen Box, instead of inside RunningContent/ScreenScaffold's own content slot.
@Composable
private fun BoxScope.ElapsedTimeOverlay(state: TimerUiState.Running) {
  Text(
    text = formatElapsed(state.elapsedSeconds),
    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
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
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

// One EdgeButton call site shared by both states, rather than a `when` with a separate EdgeButton
// per branch: AnimatedContent only cross-fades a value across recompositions of the same call
// site, so two structurally distinct EdgeButton composables in different branches would swap the
// icon instantly instead of animating between them.
@Composable
private fun TimerEdgeButton(
  state: TimerUiState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onChooseProject: () -> Unit,
) {
  val onClick: (() -> Unit)? =
    when (state) {
      is TimerUiState.Idle -> if (state.hasDefaultProject) onStart else onChooseProject
      is TimerUiState.Running -> onStop
      else -> null
    }
  if (onClick == null) return

  val haptics = LocalHapticFeedback.current
  val pressScale = remember { Animatable(1f) }
  val scope = rememberCoroutineScope()

  EdgeButton(
    onClick = {
      haptics.performHapticFeedback(HapticFeedbackType.Confirm)
      scope.launch {
        pressScale.snapTo(EDGE_BUTTON_PRESS_SCALE)
        pressScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
      }
      onClick()
    }
  ) {
    Box(
      modifier =
        Modifier.graphicsLayer {
          scaleX = pressScale.value
          scaleY = pressScale.value
        }
    ) {
      AnimatedContent(
        targetState = state is TimerUiState.Running,
        transitionSpec = {
          scaleIn(initialScale = 0.6f) + fadeIn() togetherWith
            scaleOut(targetScale = 0.6f) + fadeOut()
        },
        label = "playPauseIcon",
      ) { running ->
        if (running) {
          PauseIcon(contentDescription = stringResource(R.string.timer_stop_button))
        } else {
          PlayIcon(contentDescription = stringResource(R.string.timer_start_button))
        }
      }
    }
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
      is TimerUiState.Idle ->
        IdleContent(
          isRefreshing = isRefreshing,
          onChooseProject = onChooseProject,
          onRecent = onRecent,
          onRefresh = onRefresh,
        )
      // The elapsed-time text and description render as a screen-level overlay in TimerScreen,
      // not here - see the comment on TimerScreen's outer Box for why.
      is TimerUiState.Running ->
        RunningContent(state = state, isRefreshing = isRefreshing, onRefresh = onRefresh)
      is TimerUiState.Error ->
        ErrorContent(error = state.error, onRetry = onRetry, onGoToSettings = onGoToSettings)
    }
  }
}

// Split out of TimerScreen itself just to keep that composable under detekt's LongMethod
// threshold - the drag-offset Animatable and its CoroutineScope must still be remembered at the
// call site's position in the composition, same as if this were inlined there.
@Composable
private fun swipeToSettingsModifier(
  state: TimerUiState,
  onNavigateToSettings: () -> Unit,
): Modifier {
  // Horizontal drag offset (px): snapped 1:1 to the finger while dragging, then sprung back to 0
  // (or left to settle) on release - see swipeToSettings for where it's driven.
  val dragOffsetPx = remember { Animatable(0f) }
  val dragScope = rememberCoroutineScope()
  val gestureModifier =
    if (state is TimerUiState.Idle || state is TimerUiState.Running) {
      Modifier.swipeToSettings(onNavigateToSettings, dragOffsetPx, dragScope)
    } else {
      Modifier
    }
  return gestureModifier.graphicsLayer { translationX = dragOffsetPx.value }
}

private fun Modifier.swipeToSettings(
  onNavigateToSettings: () -> Unit,
  dragOffsetPx: Animatable<Float, AnimationVector1D>,
  dragScope: CoroutineScope,
): Modifier =
  pointerInput(onNavigateToSettings) {
    val edgeGuardPx = SETTINGS_SWIPE_EDGE_GUARD.toPx()
    val thresholdPx = SETTINGS_SWIPE_THRESHOLD.toPx()
    awaitEachGesture {
      awaitSettingsSwipe(edgeGuardPx, thresholdPx, dragOffsetPx, dragScope, onNavigateToSettings)
    }
  }

// Hand-rolled rather than detectHorizontalDragGestures: that helper consumes the touch as soon as
// horizontal slop is crossed, before this code gets a chance to check where the drag started, so
// a drag beginning in the edge-guard zone would already be stolen from the system back-swipe by
// the time direction is known. Checking the down position first, before ever calling
// awaitHorizontalTouchSlopOrCancellation, means a touch in that zone is never consumed here at all.
private suspend fun AwaitPointerEventScope.awaitSettingsSwipe(
  edgeGuardPx: Float,
  thresholdPx: Float,
  dragOffsetPx: Animatable<Float, AnimationVector1D>,
  dragScope: CoroutineScope,
  onNavigateToSettings: () -> Unit,
) {
  val down = awaitFirstDown(requireUnconsumed = false)
  if (down.position.x < edgeGuardPx) return
  var overSlop = 0f
  val drag =
    awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
      change.consume()
      overSlop = slop
    } ?: return
  var accumulated = overSlop
  // AwaitPointerEventScope is a @RestrictsSuspension scope: nothing in this function, not just
  // horizontalDrag's plain (non-suspend) onDrag callback below, can suspend on an unrelated
  // Animatable directly - every snapTo/animateTo call has to go through dragScope instead.
  // accumulated is already a running sum of real touch deltas, naturally bounded by the finger's
  // travel on screen, so no extra clamp is needed before feeding it in.
  dragScope.launch { dragOffsetPx.snapTo(accumulated) }
  var triggered = accumulated <= -thresholdPx
  if (triggered) onNavigateToSettings()
  try {
    horizontalDrag(drag.id) { change ->
      accumulated += change.positionChange().x
      dragScope.launch { dragOffsetPx.snapTo(accumulated) }
      if (!triggered && accumulated <= -thresholdPx) {
        triggered = true
        onNavigateToSettings()
      }
    }
  } finally {
    // Also runs if this gesture is cancelled outright rather than ending via horizontalDrag's
    // normal return - e.g. a background refresh resolving to Error mid-drag detaches this
    // pointerInput node entirely. Without this, dragOffsetPx would be stranded at its last dragged
    // value and the next screen would render permanently shifted. dragScope is the composition's
    // own scope, not this (possibly cancelled) gesture coroutine, so the launch still runs.
    if (triggered) {
      // Navigation already fired; the destination's own entrance transition takes over next, so
      // there's nothing to gain from animating the slide-out further than the drag itself did.
      dragScope.launch { dragOffsetPx.animateTo(0f) }
    } else {
      // Confirms "recognized but not far enough" with a visible spring-back instead of silently
      // snapping to rest.
      dragScope.launch {
        dragOffsetPx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
      }
    }
  }
}

// Deliberately plain (no tonal fill), grey rather than the theme's vivid content color, and sized
// below any named IconButtonDefaults token: Settings no longer has a persistent button (reachable
// only via the swipe gesture), so this is the sole remaining top-of-screen affordance and reads as
// a lightweight, low-emphasis utility rather than a peer of Choose-Project/Recent.
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
  val haptics = LocalHapticFeedback.current
  IconButton(
    onClick = {
      haptics.performHapticFeedback(HapticFeedbackType.Confirm)
      onClick()
    },
    modifier = Modifier.size(REFRESH_BUTTON_SIZE),
    colors = IconButtonDefaults.iconButtonColors(),
  ) {
    CompositionLocalProvider(
      LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
    ) {
      RefreshIcon(
        contentDescription = stringResource(R.string.timer_refresh_button),
        modifier = Modifier.size(REFRESH_ICON_SIZE).rotate(rotationDegrees),
      )
    }
  }
}

// Shared by Idle/Running: the sole top-of-screen entry point, above the rest of the content.
@Composable
private fun TopButtonRow(onRefresh: () -> Unit, isRefreshing: Boolean) {
  RefreshButton(onClick = onRefresh, isRefreshing = isRefreshing)
  Spacer(modifier = Modifier.height(TOP_BUTTON_ROW_GAP))
}

@Composable
private fun IdleContent(
  isRefreshing: Boolean,
  onChooseProject: () -> Unit,
  onRecent: () -> Unit,
  onRefresh: () -> Unit,
) {
  TopButtonRow(onRefresh = onRefresh, isRefreshing = isRefreshing)
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

// Just the header - the elapsed-time readout and description render as a screen-level overlay in
// TimerScreen instead (see the comment on its outer Box for why).
@Composable
private fun RunningContent(
  state: TimerUiState.Running,
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
) {
  TopButtonRow(onRefresh = onRefresh, isRefreshing = isRefreshing)
  // This row is top-anchored in the normal flow, but the elapsed-time number below it is a
  // separate, independently-centered overlay (see TimerScreen) - the two layout trees don't know
  // about each other, so a plain trailing Spacer here would add invisible space without actually
  // pulling this row further from the number. A visual-only upward offset does what's needed
  // instead: it does not change the space this row occupies, only where its content draws. Capped
  // below TOP_BUTTON_ROW_GAP (with a safety margin) so this can never climb far enough to overlap
  // the refresh button above it, which does not move - an earlier version of this scaled the offset
  // by the number's own height to also clear it on narrow screens where the number wraps to two
  // lines, but that reasoned about distance to the number's center rather than its top edge, so it
  // overshot by roughly half the number's height and ran straight into the refresh icon on normal
  // screens. See HANDOFF.md's known-debts entry on this for the still-open narrow-screen case.
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.offset(y = -PROJECT_LABEL_CENTER_GAP),
  ) {
    ProjectColorDot(color = state.projectColor)
    Spacer(modifier = Modifier.width(PROJECT_LABEL_ROW_GAP))
    Text(
      text =
        state.projectName ?: state.projectId ?: stringResource(R.string.timer_no_project_label),
      style = MaterialTheme.typography.labelMedium,
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
