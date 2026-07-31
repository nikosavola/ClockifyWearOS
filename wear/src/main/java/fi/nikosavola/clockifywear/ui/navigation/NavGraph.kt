package fi.nikosavola.clockifywear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import fi.nikosavola.clockifywear.di.AppContainer
import fi.nikosavola.clockifywear.ui.settings.SettingsScreen
import fi.nikosavola.clockifywear.ui.settings.SettingsViewModel
import fi.nikosavola.clockifywear.ui.timer.TimerScreen
import fi.nikosavola.clockifywear.ui.timer.TimerViewModel

@Composable
fun ClockifyNavHost(
  appContainer: AppContainer,
  navController: NavHostController = rememberSwipeDismissableNavController(),
) {
  AppScaffold {
    SwipeDismissableNavHost(
      navController = navController,
      startDestination = ClockifyRoutes.TIMER,
    ) {
      composable(ClockifyRoutes.TIMER) {
        val viewModel: TimerViewModel =
          viewModel(
            factory =
              viewModelFactory {
                initializer {
                  TimerViewModel(
                    repository = appContainer.repository,
                    settingsStore = appContainer.settingsStore,
                    settingsPrimed = appContainer.settingsPrimed,
                  )
                }
              }
          )
        TimerScreen(
          viewModel = viewModel,
          onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
        )
      }
      composable(ClockifyRoutes.SETTINGS) {
        val viewModel: SettingsViewModel =
          viewModel(
            factory =
              viewModelFactory {
                initializer {
                  SettingsViewModel(
                    repository = appContainer.repository,
                    settingsStore = appContainer.settingsStore,
                  )
                }
              }
          )
        SettingsScreen(viewModel = viewModel)
      }
    }
  }
}
