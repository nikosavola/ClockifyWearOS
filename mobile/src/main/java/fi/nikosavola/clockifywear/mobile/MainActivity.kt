package fi.nikosavola.clockifywear.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      CompanionTheme {
        val viewModel: SignInViewModel =
          viewModel(
            factory =
              viewModelFactory {
                initializer { SignInViewModel(PlayServicesWatchLinkClient(applicationContext)) }
              }
          )
        SignInScreen(viewModel)
      }
    }
  }
}
