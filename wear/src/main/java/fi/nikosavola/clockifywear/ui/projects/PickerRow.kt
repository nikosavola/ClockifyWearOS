package fi.nikosavola.clockifywear.ui.projects

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text

/**
 * Shared row shape for the project picker and recents list: a colored project dot as the leading
 * icon, a title, and an optional secondary label. Centralized so the two lists can't drift apart
 * visually, per the M3 styling pass.
 */
@Composable
fun PickerRow(
  title: String,
  color: Color?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  secondaryLabel: String? = null,
  transformation: SurfaceTransformation? = null,
) {
  val secondaryLabelContent: (@Composable RowScope.() -> Unit)? =
    if (!secondaryLabel.isNullOrBlank()) {
      { Text(text = secondaryLabel) }
    } else {
      null
    }
  Button(
    onClick = onClick,
    modifier = modifier,
    icon = { ProjectColorDot(color = color) },
    secondaryLabel = secondaryLabelContent,
    transformation = transformation,
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Text(text = title)
  }
}
