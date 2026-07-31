package fi.nikosavola.clockifywear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import fi.nikosavola.clockifywear.ClockifyApp
import fi.nikosavola.clockifywear.ui.navigation.ClockifyNavHost
import fi.nikosavola.clockifywear.ui.theme.ClockifyTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val appContainer = (application as ClockifyApp).appContainer
    setContent { ClockifyTheme { ClockifyNavHost(appContainer) } }
  }
}
