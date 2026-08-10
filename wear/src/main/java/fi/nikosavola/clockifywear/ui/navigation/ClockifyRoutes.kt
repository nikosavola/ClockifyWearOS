package fi.nikosavola.clockifywear.ui.navigation

/**
 * Route names as constants (not scattered string literals) so M3's pickers/recents can extend this
 * cleanly.
 */
object ClockifyRoutes {
  const val TIMER = "timer"
  const val SETTINGS = "settings"
  const val PROJECT_PICKER = "projectPicker"
  const val TASK_PICKER_PATTERN = "taskPicker/{projectId}"
  const val RECENTS = "recents"

  fun taskPicker(projectId: String) = "taskPicker/$projectId"
}
