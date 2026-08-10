package fi.nikosavola.clockifywear.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

private val PROJECT_COLOR_DOT_SIZE = 12.dp

/**
 * Small colored dot for a project's Clockify color. Shared between the project picker row and the
 * Running screen's project label so both render identically, per the M3 styling pass.
 */
@Composable
fun ProjectColorDot(color: Color?, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .size(PROJECT_COLOR_DOT_SIZE)
        .background(
          color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
          shape = CircleShape,
        )
  )
}
