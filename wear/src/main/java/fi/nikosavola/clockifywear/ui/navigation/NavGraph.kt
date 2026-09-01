package fi.nikosavola.clockifywear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
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
import fi.nikosavola.clockifywear.ui.summary.SummaryScreen
import fi.nikosavola.clockifywear.ui.summary.SummaryViewModel
import fi.nikosavola.clockifywear.ui.tasks.TaskPickerScreen
import fi.nikosavola.clockifywear.ui.tasks.TaskPickerViewModel
import fi.nikosavola.clockifywear.ui.timer.TimerScreen
import fi.nikosavola.clockifywear.ui.timer.TimerViewModel

private const val PROJECT_ID_ARG = "projectId"

// Main pager page order: Summary, Timer, Settings - Timer is the default/home page, reachable from
// Summary with a left swipe and from Settings with a right swipe (see TimerDestination).
private const val MAIN_PAGER_SUMMARY_PAGE = 0
private const val MAIN_PAGER_TIMER_PAGE = 1
private const val MAIN_PAGER_SETTINGS_PAGE = 2
private const val MAIN_PAGER_PAGE_COUNT = 3

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

// Summary/Timer/Settings share one HorizontalPager, reachable by touch swipe. Deliberately no
// rotaryScrollableBehavior here: the bezel (physical or virtual) instead scrolls the Summary page's
// own entry list (see SummaryScreen.kt) - a rotary input can only drive one thing at a time, and a
// scrollable list of entries is the more useful target for it than paging. Settings is also
// reachable as its own pushed route (ClockifyRoutes.SETTINGS, below) for error-recovery flows from
// other screens that aren't part of this pager.
@Composable
private fun TimerDestination(appContainer: AppContainer, navController: NavHostController) {
  val timerViewModel = rememberTimerViewModel(appContainer)
  val summaryViewModel = rememberSummaryViewModel(appContainer)
  val settingsViewModel = rememberSettingsViewModel(appContainer)

  val pagerState = rememberPagerState(initialPage = MAIN_PAGER_TIMER_PAGE) { MAIN_PAGER_PAGE_COUNT }
  HorizontalPager(state = pagerState) { page ->
    when (page) {
      MAIN_PAGER_SUMMARY_PAGE ->
        SummaryScreen(
          viewModel = summaryViewModel,
          onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
        )
      MAIN_PAGER_TIMER_PAGE ->
        TimerScreen(
          viewModel = timerViewModel,
          onNavigateToSettings = { navController.navigate(ClockifyRoutes.SETTINGS) },
          onNavigateToProjectPicker = { navController.navigate(ClockifyRoutes.PROJECT_PICKER) },
          onNavigateToRecents = { navController.navigate(ClockifyRoutes.RECENTS) },
        )
      MAIN_PAGER_SETTINGS_PAGE -> SettingsScreen(viewModel = settingsViewModel)
    }
  }
}

@Composable
private fun SettingsDestination(appContainer: AppContainer) {
  SettingsScreen(viewModel = rememberSettingsViewModel(appContainer))
}

// Shared by TimerDestination's Settings pager page and the standalone SettingsDestination route
// pushed for error-recovery flows (see TimerDestination's comment) - each gets its own instance,
// scoped to whichever back-stack entry composes it.
@Composable
private fun rememberTimerViewModel(appContainer: AppContainer): TimerViewModel =
  viewModel(
    factory =
      viewModelFactory {
        initializer {
          TimerViewModel(
            repository = appContainer.repository,
            settingsStore = appContainer.settingsStore,
            settingsPrimed = appContainer.settingsPrimed,
            onRunningStateChanged = { state ->
              appContainer.ongoingTimerNotifier.onTimerStateChanged(state)
              appContainer.tileUpdater.onTimerStateChanged(state)
            },
          )
        }
      }
  )

@Composable
private fun rememberSummaryViewModel(appContainer: AppContainer): SummaryViewModel =
  viewModel(
    factory =
      viewModelFactory {
        initializer {
          SummaryViewModel(
            repository = appContainer.repository,
            settingsPrimed = appContainer.settingsPrimed,
          )
        }
      }
  )

@Composable
private fun rememberSettingsViewModel(appContainer: AppContainer): SettingsViewModel =
  viewModel(
    factory =
      viewModelFactory {
        initializer {
          SettingsViewModel(
            repository = appContainer.repository,
            settingsStore = appContainer.settingsStore,
            onSignedOut = {
              appContainer.ongoingTimerNotifier.cancel()
              appContainer.tileUpdater.refresh()
            },
          )
        }
      }
  )

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
