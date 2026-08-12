package fi.nikosavola.clockifywear.tile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
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
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import fi.nikosavola.clockifywear.ClockifyApp
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.Settings
import fi.nikosavola.clockifywear.data.SettingsStore
import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.ui.MainActivity
import fi.nikosavola.clockifywear.ui.projects.parseProjectColor
import fi.nikosavola.clockifywear.ui.timer.formatElapsedHoursMinutesUnits
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Elapsed time here is a static snapshot as of the last tile build, not a live tick:
// androidx.wear.protolayout has no ready-made "elapsed since an Instant" dynamic type analogous to
// androidx.wear.ongoing.Status.StopwatchPart, and building a custom dynamic expression binding for
// it wasn't worth it for v1. Displayed with formatElapsedHoursMinutesUnits (whole minutes), not
// seconds, to match this cadence honestly. The freshness interval itself is state-dependent: short
// while a timer is running (the displayed minute can go stale), much longer while idle.
private val FRESHNESS_INTERVAL_RUNNING_MILLIS = TimeUnit.MINUTES.toMillis(1)
private val FRESHNESS_INTERVAL_IDLE_MILLIS = TimeUnit.MINUTES.toMillis(30)

// Registered in onTileResourcesRequest, referenced by id from the edge button's icon() call.
// Manual id-to-resource mapping because androidx.wear.tiles:tiles 1.6.2's ResourcesRequest has no
// getScope() for the newer ProtoLayoutScope-backed auto-registration.
private const val PLAY_ICON_ID = "ic_tile_play"
private const val PAUSE_ICON_ID = "ic_tile_pause"

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
 * The swipeable Tile surface showing the current running timer at a glance. Bound on demand by the
 * system, possibly with the main app process already evicted, so state is always read fresh from
 * [ClockifyRepository] here rather than trusted from any in-memory ViewModel.
 */
class ClockifyTileService : TileService() {
  // SupervisorJob: an exception from one onTileRequest's coroutine must not cancel this scope's
  // Job and silently take every subsequent tile refresh down with it.
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // One instance per live service, so its Mutex actually serializes concurrent onTileRequest calls
  // against this same bound service - see TileClickResolver's doc comment.
  private val clickResolver = TileClickResolver()

  override fun onTileRequest(
    requestParams: RequestBuilders.TileRequest
  ): ListenableFuture<TileBuilders.Tile> {
    val future = CompletableListenableFuture<TileBuilders.Tile>()
    val appContainer = (applicationContext as ClockifyApp).appContainer
    // LAZY, plus wiring future.onCancel before starting: guarantees the Job is assigned before the
    // coroutine body can run, so a cancel() racing the launch can never see a null job. Same
    // ordering reasoning as TimerViewModel.start()'s use of LAZY.
    val job =
      serviceScope.launch(start = CoroutineStart.LAZY) {
        try {
          future.set(buildTile(requestParams, appContainer.repository, appContainer.settingsStore))
        } catch (e: CancellationException) {
          throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
          // Deliberately generic: buildTile can throw from OkHttp, from the
          // protolayout-material3 builders, or from anything else in its call graph, and any of
          // those must complete the future exceptionally rather than escape this launch and crash
          // the whole app process from a background tile refresh.
          future.setException(e)
        }
      }
    future.onCancel = { job.cancel() }
    job.start()
    return future
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onTileResourcesRequest(
    requestParams: RequestBuilders.ResourcesRequest
  ): ListenableFuture<ResourceBuilders.Resources> {
    val future = CompletableListenableFuture<ResourceBuilders.Resources>()
    future.set(
      ResourceBuilders.Resources.Builder()
        .setVersion(RESOURCES_VERSION)
        .addIdToImageMapping(PLAY_ICON_ID, androidResourceImage(R.drawable.ic_tile_play))
        .addIdToImageMapping(PAUSE_ICON_ID, androidResourceImage(R.drawable.ic_tile_pause))
        .build()
    )
    return future
  }

  private fun androidResourceImage(resId: Int): ResourceBuilders.ImageResource =
    ResourceBuilders.ImageResource.Builder()
      .setAndroidResourceByResId(
        ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build()
      )
      .build()

  private suspend fun buildTile(
    requestParams: RequestBuilders.TileRequest,
    repository: ClockifyRepository,
    settingsStore: SettingsStore,
  ): TileBuilders.Tile {
    val settings = settingsStore.currentSettings()
    val outcome = resolveClick(requestParams, repository, settings, settingsStore)
    val entry = (outcome.runningState as? TileRunningState.Confirmed)?.entry
    val statusUnknown = outcome.runningState is TileRunningState.Unknown
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
    val token = Random.nextLong().toString()
    val edgeButtonClickable =
      when {
        entry != null -> loadActionClickable(buildStopClickId(token))
        hasDefaultProject -> loadActionClickable(buildStartClickId(token))
        else -> launchApp
      }

    val rootLayout =
      materialScope(this, requestParams.deviceConfiguration) {
        primaryLayout(
          titleSlot = {
            text(getString(R.string.tile_title).layoutString, typography = Typography.LABEL_SMALL)
          },
          onClick = launchApp,
          mainSlot = { mainSlotContent(entry, project, statusUnknown, outcome.actionFailed) },
          bottomSlot = {
            edgeButtonContent(isRunning = entry != null, onClick = edgeButtonClickable)
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

  private suspend fun resolveClick(
    requestParams: RequestBuilders.TileRequest,
    repository: ClockifyRepository,
    settings: Settings,
    settingsStore: SettingsStore,
  ): TileClickOutcome =
    clickResolver.resolve(
      lastClickableId = requestParams.currentState.lastClickableId,
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
              .setPackageName(packageName)
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
    onClick: ModifiersBuilders.Clickable,
  ): LayoutElementBuilders.LayoutElement {
    val description =
      getString(if (isRunning) R.string.timer_stop_button else R.string.timer_start_button)
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
    actionFailed: Boolean,
  ): LayoutElementBuilders.LayoutElement =
    when {
      // actionFailed takes priority: the user just tapped and needs to know the tap didn't stick,
      // which matters more here than the general "we don't know current state" signal below.
      actionFailed -> {
        projectRow(projectColor = null, label = getString(R.string.tile_action_failed))
      }
      statusUnknown -> {
        projectRow(projectColor = null, label = getString(R.string.tile_status_unknown))
      }
      entry != null -> {
        val elapsedSeconds = Duration.between(entry.timeInterval.start, Instant.now()).seconds
        LayoutElementBuilders.Column.Builder()
          .setWidth(DimensionBuilders.expand())
          .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
          .addContent(
            projectRow(
              projectColor = project?.color?.let(::parseProjectColor),
              label = project?.name ?: getString(R.string.timer_no_project_label),
            )
          )
          .addContent(
            text(
              formatElapsedHoursMinutesUnits(elapsedSeconds).layoutString,
              typography = Typography.NUMERAL_MEDIUM,
            )
          )
          .build()
      }
      else -> {
        projectRow(projectColor = null, label = getString(R.string.tile_no_timer_running))
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

  /**
   * Minimal settable [ListenableFuture]: this module only has Guava's API-only `listenablefuture`
   * artifact on its classpath, not the full Guava library that ships
   * [com.google.common.util.concurrent.SettableFuture], so bridging a suspend result into
   * TileService's Future-based contract needs a small local implementation instead. Backed by
   * [CompletableFuture] rather than reinventing listener bookkeeping.
   */
  private class CompletableListenableFuture<T> : ListenableFuture<T> {
    private val delegate = CompletableFuture<T>()

    // Set by the caller right after construction, before this future is returned to the system:
    // lets cancel() actually stop the in-flight coroutine doing the real work, not just the
    // CompletableFuture wrapper around it.
    var onCancel: () -> Unit = {}

    fun set(value: T) {
      delegate.complete(value)
    }

    fun setException(t: Throwable) {
      delegate.completeExceptionally(t)
    }

    override fun addListener(listener: Runnable, executor: Executor) {
      delegate.whenCompleteAsync({ _, _ -> listener.run() }, executor)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
      val cancelled = delegate.cancel(mayInterruptIfRunning)
      if (cancelled) onCancel()
      return cancelled
    }

    override fun isCancelled(): Boolean = delegate.isCancelled

    override fun isDone(): Boolean = delegate.isDone

    override fun get(): T = delegate.get()

    override fun get(timeout: Long, unit: TimeUnit): T = delegate.get(timeout, unit)
  }
}
