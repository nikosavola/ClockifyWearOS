package fi.nikosavola.clockifywear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import fi.nikosavola.clockifywear.ClockifyApp
import fi.nikosavola.clockifywear.ui.navigation.ClockifyNavHost
import fi.nikosavola.clockifywear.ui.theme.ClockifyTheme

class MainActivity : ComponentActivity() {
  // Must be registered unconditionally as a property, not lazily inside onCreate: the contract
  // requires registration before the activity reaches STARTED, regardless of whether the
  // permission ends up being requested.
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestNotificationPermissionIfNeeded()

    val appContainer = (application as ClockifyApp).appContainer
    setContent { ClockifyTheme { ClockifyNavHost(appContainer) } }
  }

  // No rationale UI for v1: a single system prompt is enough to cover the ongoing-activity
  // notification (WO-V4). Only needed on API 33+; POST_NOTIFICATIONS didn't exist before that.
  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}
