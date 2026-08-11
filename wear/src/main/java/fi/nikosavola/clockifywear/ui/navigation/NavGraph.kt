package fi.nikosavola.clockifywear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import fi.nikosavola.clockifywear.di.AppContainer
import fi.nikosavola.clockifywear.ui.projects.ProjectPickerScreen
import fi.nikosavola.clockifywear.ui.projects.ProjectPickerViewModel
import fi.nikosavola.clockifywear.ui.recents.RecentsScreen
import fi.nikosavola.clockifywear.ui.recents.RecentsViewModel
import fi.nikosavola.clockifywear.ui.settings.SettingsScreen
import fi.nikosavola.clockifywear.ui.settings.SettingsViewModel
import fi.nikosavola.clockifywear.ui.tasks.TaskPickerScreen
import fi.nikosavola.clockifywear.ui.tasks.TaskPickerViewModel
import fi.nikosavola.clockifywear.ui.timer.TimerScreen
import fi.nikosavola.clockifywear.ui.timer.TimerViewModel

private const val PROJECT_ID_ARG = "projectId"

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
      composable(ClockifyRoutes.TIMER) { TimerDestination(appContainer, navController) }
      composable(ClockifyRoutes.SETTINGS) { SettingsDestination(appContainer) }
      composable(ClockifyRoutes.PROJECT_PICKER) {
        ProjectPickerDestination(appContainer, navController)
      }
      composable(
        ClockifyRoutes.TASK_PICKER_PATTERN,
        arguments = listOf(navArgument(PROJECT_ID_ARG) { type = NavType.StringType }),
      ) { backStackEntry ->
        val projectId = backStackEntry.arguments?.getString(PROJECT_ID_ARG).orEmpty()
        TaskPickerDestination(appContainer, navController, projectId)
      }
      composable(ClockifyRoutes.RECENTS) { RecentsDestination(appContainer, navController) }
    }
  }
}

@Composable
private fun TimerDestination(appContainer: AppContainer, navController: NavHostController) {
  val viewModel: TimerViewModel =
    viewModel(
      factory =
        viewModelFactory {
          initializer {
            TimerViewModel(
              repository = appContainer.repository,
              settingsStore = appContainer.settingsStore,
              settingsPrimed = appContainer.settingsPrimed,
              onRunningStateChanged = appContainer.ongoingTimerNotifier::onTimerStateChanged,
            )
          }
        }
    )
  TimerScreen(
    viewModel = viewModel,
    onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
    onNavigateToProjectPicker = { navController.navigate(ClockifyRoutes.PROJECT_PICKER) },
    onNavigateToRecents = { navController.navigate(ClockifyRoutes.RECENTS) },
  )
}

@Composable
private fun SettingsDestination(appContainer: AppContainer) {
  val viewModel: SettingsViewModel =
    viewModel(
      factory =
        viewModelFactory {
          initializer {
            SettingsViewModel(
              repository = appContainer.repository,
              settingsStore = appContainer.settingsStore,
              onSignedOut = appContainer.ongoingTimerNotifier::cancel,
            )
          }
        }
    )
  SettingsScreen(viewModel = viewModel)
}

@Composable
private fun ProjectPickerDestination(appContainer: AppContainer, navController: NavHostController) {
  val viewModel: ProjectPickerViewModel =
    viewModel(
      factory =
        viewModelFactory {
          initializer {
            ProjectPickerViewModel(
              repository = appContainer.repository,
              settingsStore = appContainer.settingsStore,
              settingsPrimed = appContainer.settingsPrimed,
            )
          }
        }
    )
  ProjectPickerScreen(
    viewModel = viewModel,
    onProjectSelected = { projectId ->
      navController.navigate(ClockifyRoutes.taskPicker(projectId))
    },
    onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
  )
}

@Composable
private fun TaskPickerDestination(
  appContainer: AppContainer,
  navController: NavHostController,
  projectId: String,
) {
  val viewModel: TaskPickerViewModel =
    viewModel(
      factory =
        viewModelFactory {
          initializer {
            TaskPickerViewModel(
              repository = appContainer.repository,
              settingsStore = appContainer.settingsStore,
              projectId = projectId,
              settingsPrimed = appContainer.settingsPrimed,
            )
          }
        }
    )
  TaskPickerScreen(
    viewModel = viewModel,
    onStarted = { navController.popBackStack(ClockifyRoutes.TIMER, inclusive = false) },
    onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
  )
}

@Composable
private fun RecentsDestination(appContainer: AppContainer, navController: NavHostController) {
  val viewModel: RecentsViewModel =
    viewModel(
      factory =
        viewModelFactory {
          initializer {
            RecentsViewModel(
              repository = appContainer.repository,
              settingsStore = appContainer.settingsStore,
              settingsPrimed = appContainer.settingsPrimed,
            )
          }
        }
    )
  RecentsScreen(
    viewModel = viewModel,
    onStarted = { navController.popBackStack(ClockifyRoutes.TIMER, inclusive = false) },
    onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
  )
}
