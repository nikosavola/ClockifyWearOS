package fi.nikosavola.clockifywear.ui.projects

import androidx.compose.ui.graphics.Color

private const val HEX_COLOR_LENGTH = 7 // "#RRGGBB"
private const val RED_START = 1
private const val GREEN_START = 3
private const val BLUE_START = 5
private const val COMPONENT_LENGTH = 2
private const val HEX_RADIX = 16

/**
 * Parses a Clockify project color like "#1976D2" into a [Color]. Returns null for anything
 * malformed rather than throwing: a bad color string from the API must never crash the picker.
 * Manual parsing (not `android.graphics.Color.parseColor`) keeps this framework-free and testable
 * with plain JUnit.
 */
fun parseProjectColor(hex: String?): Color? {
  if (hex == null || hex.length != HEX_COLOR_LENGTH || hex[0] != '#') return null
  val red = hex.substring(RED_START, RED_START + COMPONENT_LENGTH).toIntOrNull(HEX_RADIX)
  val green = hex.substring(GREEN_START, GREEN_START + COMPONENT_LENGTH).toIntOrNull(HEX_RADIX)
  val blue = hex.substring(BLUE_START, BLUE_START + COMPONENT_LENGTH).toIntOrNull(HEX_RADIX)
  return if (red != null && green != null && blue != null) {
    Color(red = red, green = green, blue = blue)
  } else {
    null
  }
}
