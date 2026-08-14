package fi.nikosavola.clockifywear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyError

private val ERROR_CONTENT_HORIZONTAL_PADDING = 18.dp
private val ERROR_CONTENT_GAP = 10.dp
private val ERROR_ICON_SIZE = 36.dp

// See TimerScreen's own note on this: material-icons-extended isn't on the classpath and isn't
// worth adding for a couple of glyphs, so these are hand-drawn like every other icon in the app.
private const val PERSON_HEAD_RADIUS_FRACTION = 0.22f
private const val PERSON_HEAD_Y_FACTOR = 1.15f
private const val PERSON_SHOULDER_RADIUS_FRACTION = 0.46f
private val WARNING_RING_STROKE_WIDTH = 2.5.dp
private const val WARNING_BAR_TOP_FACTOR = 0.32f
private const val WARNING_BAR_BOTTOM_FACTOR = 0.6f
private const val WARNING_DOT_CENTER_FACTOR = 0.74f

/**
 * Shared error display for every screen: an icon, the message, and either a retry button, or a
 * go-to-Settings button when [ClockifyError] means the stored identity is missing or rejected.
 */
@Composable
fun ErrorContent(error: ClockifyError, onRetry: () -> Unit, onGoToSettings: () -> Unit) {
  val signInRequired = requiresSignIn(error)
  Column(
    modifier = Modifier.padding(horizontal = ERROR_CONTENT_HORIZONTAL_PADDING),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(ERROR_CONTENT_GAP),
  ) {
    val actionLabel =
      stringResource(
        if (signInRequired) R.string.timer_go_to_settings_button else R.string.timer_retry_button
      )
    if (signInRequired) {
      PersonIcon(contentDescription = actionLabel)
    } else {
      WarningIcon(contentDescription = actionLabel)
    }
    Text(
      text = errorMessage(error),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Button(onClick = if (signInRequired) onGoToSettings else onRetry) { Text(text = actionLabel) }
  }
}

// Shared scaffolding for the hand-drawn glyphs below: fixed size, current content color, a11y
// label - same pattern as TimerScreen's private GlyphIcon, duplicated rather than shared across
// files for a couple of icons.
@Composable
private fun ErrorGlyph(
  contentDescription: String,
  modifier: Modifier = Modifier,
  draw: DrawScope.(color: Color) -> Unit,
) {
  val color = LocalContentColor.current
  Canvas(
    modifier =
      modifier.size(ERROR_ICON_SIZE).semantics { this.contentDescription = contentDescription }
  ) {
    draw(color)
  }
}

@Composable
private fun PersonIcon(contentDescription: String, modifier: Modifier = Modifier) {
  ErrorGlyph(contentDescription, modifier) { color ->
    val headRadius = size.minDimension * PERSON_HEAD_RADIUS_FRACTION
    val headCenter = Offset(size.width / 2f, headRadius * PERSON_HEAD_Y_FACTOR)
    drawCircle(color = color, radius = headRadius, center = headCenter)
    // The shoulders are the top (dome) half of a circle whose flat diameter line sits on the
    // icon's bottom edge, so the curve bulges up toward the head rather than spilling outside.
    val shoulderRadius = size.width * PERSON_SHOULDER_RADIUS_FRACTION
    drawArc(
      color = color,
      startAngle = 180f,
      sweepAngle = 180f,
      useCenter = true,
      topLeft = Offset(size.width / 2f - shoulderRadius, size.height - shoulderRadius),
      size = Size(shoulderRadius * 2f, shoulderRadius * 2f),
    )
  }
}

@Composable
private fun WarningIcon(contentDescription: String, modifier: Modifier = Modifier) {
  ErrorGlyph(contentDescription, modifier) { color ->
    val strokeWidth = WARNING_RING_STROKE_WIDTH.toPx()
    val radius = (minOf(size.width, size.height) - strokeWidth) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
    drawLine(
      color = color,
      start = Offset(center.x, size.height * WARNING_BAR_TOP_FACTOR),
      end = Offset(center.x, size.height * WARNING_BAR_BOTTOM_FACTOR),
      strokeWidth = strokeWidth,
      cap = StrokeCap.Round,
    )
    drawCircle(
      color = color,
      radius = strokeWidth / 2f,
      center = Offset(center.x, size.height * WARNING_DOT_CENTER_FACTOR),
    )
  }
}
