package fi.nikosavola.clockifywear.tile

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconEdgeButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.TileBuilders
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.ui.MainActivity
import fi.nikosavola.clockifywear.ui.projects.parseProjectColor
import fi.nikosavola.clockifywear.ui.requiresSignIn
import fi.nikosavola.clockifywear.ui.timer.elapsedHoursAndMinutes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// Elapsed time here is a static snapshot as of the last tile build, not a live tick:
// androidx.wear.protolayout has no ready-made "elapsed since an Instant" dynamic type analogous to
// androidx.wear.ongoing.Status.StopwatchPart, and building a custom dynamic expression binding for
// it wasn't worth it for v1. Displayed with elapsedHoursAndMinutes (whole minutes), not seconds,
// to match this cadence honestly. The freshness interval itself is state-dependent: short
// while a timer is running (the displayed minute can go stale), much longer while idle.
private val FRESHNESS_INTERVAL_RUNNING_MILLIS = TimeUnit.MINUTES.toMillis(1)
private val FRESHNESS_INTERVAL_IDLE_MILLIS = TimeUnit.MINUTES.toMillis(30)

// Referenced by id from the edge button's icon() call, and registered by resources() below.
// Manual id-to-resource mapping because androidx.wear.tiles:tiles 1.6.2's ResourcesRequest has no
// getScope() for the newer ProtoLayoutScope-backed auto-registration.
internal const val PLAY_ICON_ID = "ic_tile_play"
internal const val PAUSE_ICON_ID = "ic_tile_pause"

// Bumped from the old text-only tile's "1": the renderer caches resources by this string and only
// calls onTileResourcesRequest again once it changes, so a device that already cached the old,
// image-less set needs a version bump to pick up the new icons.
private const val RESOURCES_VERSION = "2"

private const val PROJECT_DOT_DIAMETER_DP = 10f
private val PROJECT_LABEL_ROW_GAP = DimensionBuilders.dp(4f)
// Static fallback, not read from MaterialTheme: protolayout's LayoutColor and Compose's Color have
// no conversion between them, so unlike ProjectColorDot (a real Composable) this can't fall back to
// a theme token. Chosen to sit close to the M3 dark theme's onSurfaceVariant tone.
private const val DEFAULT_DOT_ARGB = 0xFF9AA0A6.toInt()

/**
 * Builds the Tile's content for one request. Separate from [ClockifyTileService] so the
 * state-to-content mapping can be exercised without a bound service, and so the per-service
 * [TileClickResolver] - whose Mutex only serializes within one instance - has an owner with exactly
 * one instance per service.
 */
class TileContentBuilder(private val context: Context) {
  private val clickResolver = TileClickResolver()

  /**
   * Reads the current state from [repository] and renders it. Bound on demand by the system,
   * possibly with the main app process already evicted, so state is always read fresh here rather
   * than trusted from any in-memory ViewModel.
   */
  suspend fun build(
    deviceConfiguration: DeviceParametersBuilders.DeviceParameters,
    lastClickableId: String?,
    repository: ClockifyRepository,
    settingsStore: SettingsStore,
  ): TileBuilders.Tile {
    val settings = settingsStore.currentSettings()
    val outcome = resolveClick(lastClickableId, repository, settings, settingsStore)
    val entry = (outcome.runningState as? TileRunningState.Confirmed)?.entry
    val unknownState = outcome.runningState as? TileRunningState.Unknown
    val statusUnknown = unknownState != null
    // Distinguishes "signed out entirely" from any other transient fetch failure: a Play-button
    // LoadAction retry can never succeed while signed out, so that case needs LaunchAction to the
    // app's Settings screen instead - see requiresSignIn's doc comment.
    val signInRequired = unknownState?.error?.let(::requiresSignIn) ?: false
    val project = entry?.projectId?.let { resolveProject(repository, it) }
    val hasDefaultProject = settings.defaultProjectId != null
    val freshnessIntervalMillis =
      // Unknown retries on the running cadence too: staying wrong for up to 30 minutes after a
      // transient fetch failure is worse than a slightly more frequent retry.
      if (entry != null || statusUnknown) {
        FRESHNESS_INTERVAL_RUNNING_MILLIS
      } else {
        FRESHNESS_INTERVAL_IDLE_MILLIS
      }

    val launchApp = launchAppClickable()

    // A fresh token every build makes each rendered edge button's click id one-shot; see
    // TileClickResolver. Idle-with-no-default-project has no LoadAction equivalent of the real
    // app's onChooseProject navigation, so it falls back to launchApp instead of no-op-ing on tap.
    // Signed-out is the same story: a LoadAction start/stop retry can't succeed while signed out,
    // so it also falls back to launchApp, routing the user to the app's Settings screen instead.
    val token = Random.nextLong().toString()
    val edgeButtonClickable =
      when {
        entry != null -> loadActionClickable(buildStopClickId(token))
        signInRequired -> launchApp
        hasDefaultProject -> loadActionClickable(buildStartClickId(token))
        else -> launchApp
      }

    val rootLayout =
      materialScope(context, deviceConfiguration) {
        primaryLayout(
          titleSlot = {
            text(
              context.getString(R.string.tile_title).layoutString,
              typography = Typography.LABEL_SMALL,
            )
          },
          onClick = launchApp,
          mainSlot = {
            mainSlotContent(entry, project, statusUnknown, signInRequired, outcome.actionFailed)
          },
          bottomSlot = {
            edgeButtonContent(
              isRunning = entry != null,
              signInRequired = signInRequired,
              onClick = edgeButtonClickable,
            )
          },
        )
      }

    return TileBuilders.Tile.Builder()
      .setResourcesVersion(RESOURCES_VERSION)
      .setFreshnessIntervalMillis(freshnessIntervalMillis)
      .setTileTimeline(
        TimelineBuilders.Timeline.Builder()
          .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
              .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(rootLayout).build())
              .build()
          )
          .build()
      )
      .build()
  }

  /** The images [build]'s edge button refers to by id, under [RESOURCES_VERSION]. */
  fun resources(): ResourceBuilders.Resources =
    ResourceBuilders.Resources.Builder()
      .setVersion(RESOURCES_VERSION)
      .addIdToImageMapping(PLAY_ICON_ID, androidResourceImage(R.drawable.ic_tile_play))
      .addIdToImageMapping(PAUSE_ICON_ID, androidResourceImage(R.drawable.ic_tile_pause))
      .build()

  private suspend fun resolveClick(
    lastClickableId: String?,
    repository: ClockifyRepository,
    settings: Settings,
    settingsStore: SettingsStore,
  ): TileClickOutcome =
    clickResolver.resolve(
      lastClickableId = lastClickableId,
      tokenStore =
        TileClickTokenStore(
          // Re-read fresh inside the resolver's Mutex-guarded section, not the `settings` snapshot
          // taken before the lock: two concurrent calls both starting from a stale "not consumed"
          // read would otherwise both pass this check regardless of the Mutex.
          isConsumed = { token ->
            settingsStore.currentSettings().lastConsumedTileClickToken == token
          },
          markConsumed = { token -> settingsStore.setLastConsumedTileClickToken(token) },
        ),
      hasDefaultProject = settings.defaultProjectId != null,
      repository =
        TileActionRepository(
          fetchRunningEntry = repository::fetchRunningEntry,
          startTimer = {
            val projectId = settings.defaultProjectId
            checkNotNull(projectId) { "startTimer requested without a default project configured" }
            repository.startTimer(projectId = projectId, taskId = settings.defaultTaskId)
          },
          stopTimer = repository::stopTimer,
        ),
    )

  private fun launchAppClickable(): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder()
      .setId("open_clockify")
      .setOnClick(
        ActionBuilders.LaunchAction.Builder()
          .setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder()
              .setPackageName(context.packageName)
              .setClassName(MainActivity::class.java.name)
              .build()
          )
          .build()
      )
      .build()

  private fun loadActionClickable(id: String): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder()
      .setId(id)
      .setOnClick(ActionBuilders.LoadAction.Builder().build())
      .build()

  private fun androidx.wear.protolayout.material3.MaterialScope.edgeButtonContent(
    isRunning: Boolean,
    signInRequired: Boolean,
    onClick: ModifiersBuilders.Clickable,
  ): LayoutElementBuilders.LayoutElement {
    // There's no dedicated "open app"/sign-in icon resource, so the signed-out case keeps the Play
    // glyph (it does lead to the same place a real start would) but must not claim the description
    // of an action it can't perform - the button launches the app to sign in, not a LoadAction
    // start.
    val description =
      context.getString(
        when {
          isRunning -> R.string.timer_stop_button
          signInRequired -> R.string.settings_sign_in_button
          else -> R.string.timer_start_button
        }
      )
    return iconEdgeButton(
      onClick = onClick,
      modifier = LayoutModifier.contentDescription(description),
    ) {
      icon(if (isRunning) PAUSE_ICON_ID else PLAY_ICON_ID)
    }
  }

  private fun androidx.wear.protolayout.material3.MaterialScope.mainSlotContent(
    entry: TimeEntryDto?,
    project: ProjectDto?,
    statusUnknown: Boolean,
    signInRequired: Boolean,
    actionFailed: Boolean,
  ): LayoutElementBuilders.LayoutElement =
    when {
      // signInRequired takes priority over actionFailed: a failed start/stop tap while signed out
      // is itself caused by being signed out, so naming that cause is strictly more useful than
      // the generic "action failed" - and it matches the edge button, which has already switched
      // to launchApp for this case instead of retrying the same doomed LoadAction.
      signInRequired -> {
        projectRow(projectColor = null, label = context.getString(R.string.tile_sign_in_required))
      }
      // actionFailed takes priority over the generic statusUnknown below: the user just tapped
      // and needs to know the tap didn't stick, which matters more here than "we don't know
      // current state" for any other transient failure.
      actionFailed -> {
        projectRow(projectColor = null, label = context.getString(R.string.tile_action_failed))
      }
      statusUnknown -> {
        projectRow(projectColor = null, label = context.getString(R.string.tile_status_unknown))
      }
      entry != null -> {
        val elapsedSeconds = Duration.between(entry.timeInterval.start, Instant.now()).seconds
        val (hours, minutes) = elapsedHoursAndMinutes(elapsedSeconds)
        val durationText =
          when {
            hours == 0L -> context.getString(R.string.tile_duration_minutes_only, minutes)
            minutes == 0L -> context.getString(R.string.tile_duration_hours_only, hours)
            else -> context.getString(R.string.tile_duration_hours_minutes, hours, minutes)
          }
        LayoutElementBuilders.Column.Builder()
          .setWidth(DimensionBuilders.expand())
          .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
          .addContent(
            projectRow(
              projectColor = project?.color?.let(::parseProjectColor),
              label = project?.name ?: context.getString(R.string.timer_no_project_label),
            )
          )
          .addContent(text(durationText.layoutString, typography = Typography.NUMERAL_MEDIUM))
          .build()
      }
      else -> {
        projectRow(projectColor = null, label = context.getString(R.string.tile_no_timer_running))
      }
    }

  // Same dot-plus-label row shape for both states (see RunningContent/ProjectColorDot in
  // TimerScreen.kt), just with a muted fallback color for the non-running labels - visual
  // continuity with the real app's Running screen rather than a plain unstyled sentence.
  private fun androidx.wear.protolayout.material3.MaterialScope.projectRow(
    projectColor: Color?,
    label: String,
  ): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Row.Builder()
      .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
      .addContent(colorDot(projectColor))
      .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(PROJECT_LABEL_ROW_GAP).build())
      .addContent(
        text(
          label.layoutString,
          typography = Typography.TITLE_MEDIUM,
          color = colorScheme.onSurfaceVariant,
        )
      )
      .build()

  private fun colorDot(color: Color?): LayoutElementBuilders.LayoutElement {
    val argb = color?.toArgb() ?: DEFAULT_DOT_ARGB
    val diameter = DimensionBuilders.dp(PROJECT_DOT_DIAMETER_DP)
    return LayoutElementBuilders.Box.Builder()
      .setWidth(diameter)
      .setHeight(diameter)
      .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
          .setBackground(
            ModifiersBuilders.Background.Builder()
              .setColor(ColorBuilders.ColorProp.Builder(argb).build())
              .setCorner(
                ModifiersBuilders.Corner.Builder()
                  .setRadius(DimensionBuilders.dp(PROJECT_DOT_DIAMETER_DP / 2f))
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()
  }

  private fun androidResourceImage(resId: Int): ResourceBuilders.ImageResource =
    ResourceBuilders.ImageResource.Builder()
      .setAndroidResourceByResId(
        ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build()
      )
      .build()

  // Cosmetic-only lookup: a failed or unresolved project must never crash the tile, same
  // tolerance as TimerViewModel.resolveProject. Resolves name and color from the same call so no
  // second network request is made just to look up the color.
  private suspend fun resolveProject(
    repository: ClockifyRepository,
    projectId: String,
  ): ProjectDto? =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value.firstOrNull { it.id == projectId }
      is ClockifyResult.Failure -> null
    }
}
