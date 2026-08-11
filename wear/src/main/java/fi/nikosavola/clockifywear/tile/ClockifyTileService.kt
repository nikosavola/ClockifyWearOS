package fi.nikosavola.clockifywear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import fi.nikosavola.clockifywear.ClockifyApp
import fi.nikosavola.clockifywear.R
import fi.nikosavola.clockifywear.data.ClockifyRepository
import fi.nikosavola.clockifywear.data.ClockifyResult
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.ui.MainActivity
import fi.nikosavola.clockifywear.ui.timer.formatElapsedMinutes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
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
// it wasn't worth it for v1. Displayed at minute precision (formatElapsedMinutes), not seconds, to
// match this cadence honestly - HH:MM:SS would imply live-second accuracy this snapshot can't
// deliver. The freshness interval itself is state-dependent: short while a timer is running (the
// displayed minute can go stale), much longer while idle (nothing on screen changes, so there's no
// reason to pay for a network round trip every minute).
private val FRESHNESS_INTERVAL_RUNNING_MILLIS = TimeUnit.MINUTES.toMillis(1)
private val FRESHNESS_INTERVAL_IDLE_MILLIS = TimeUnit.MINUTES.toMillis(30)

/**
 * The swipeable Tile surface showing the current running timer at a glance. Bound on demand by the
 * system, possibly with the main app process already evicted, so state is always read fresh from
 * [ClockifyRepository] here rather than trusted from any in-memory ViewModel.
 */
class ClockifyTileService : TileService() {
  // SupervisorJob: an exception from one onTileRequest's coroutine must not cancel this scope's
  // Job and silently take every subsequent tile refresh down with it.
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onTileRequest(
    requestParams: RequestBuilders.TileRequest
  ): ListenableFuture<TileBuilders.Tile> {
    val future = CompletableListenableFuture<TileBuilders.Tile>()
    val repository = (applicationContext as ClockifyApp).appContainer.repository
    // LAZY, plus wiring future.onCancel before starting: guarantees the Job is assigned before the
    // coroutine body can run, so a cancel() racing the launch can never see a null job. Same
    // ordering reasoning as TimerViewModel.start()'s use of LAZY.
    val job =
      serviceScope.launch(start = CoroutineStart.LAZY) {
        try {
          future.set(buildTile(requestParams, repository))
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
    // Text-only tile: no images to register.
    val future = CompletableListenableFuture<ResourceBuilders.Resources>()
    future.set(ResourceBuilders.Resources.Builder().setVersion("1").build())
    return future
  }

  private suspend fun buildTile(
    requestParams: RequestBuilders.TileRequest,
    repository: ClockifyRepository,
  ): TileBuilders.Tile {
    val entry = (repository.fetchRunningEntry() as? ClockifyResult.Success)?.value
    val projectName = entry?.projectId?.let { resolveProjectName(repository, it) }
    val freshnessIntervalMillis =
      if (entry != null) FRESHNESS_INTERVAL_RUNNING_MILLIS else FRESHNESS_INTERVAL_IDLE_MILLIS

    val launchApp =
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

    val rootLayout =
      materialScope(this, requestParams.deviceConfiguration) {
        primaryLayout(
          titleSlot = {
            text(getString(R.string.tile_title).layoutString, typography = Typography.LABEL_SMALL)
          },
          onClick = launchApp,
          mainSlot = { mainSlotContent(entry, projectName) },
        )
      }

    return TileBuilders.Tile.Builder()
      .setResourcesVersion("1")
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

  private fun androidx.wear.protolayout.material3.MaterialScope.mainSlotContent(
    entry: TimeEntryDto?,
    projectName: String?,
  ): LayoutElementBuilders.LayoutElement =
    if (entry != null) {
      val elapsedSeconds = Duration.between(entry.timeInterval.start, Instant.now()).seconds
      LayoutElementBuilders.Column.Builder()
        .setWidth(DimensionBuilders.expand())
        .addContent(
          text(
            (projectName ?: entry.projectId ?: getString(R.string.timer_no_project_label))
              .layoutString,
            typography = Typography.TITLE_MEDIUM,
          )
        )
        .addContent(
          text(
            formatElapsedMinutes(elapsedSeconds).layoutString,
            typography = Typography.DISPLAY_SMALL,
          )
        )
        .build()
    } else {
      text(
        getString(R.string.tile_no_timer_running).layoutString,
        typography = Typography.BODY_MEDIUM,
      )
    }

  // Cosmetic-only lookup: a failed or unresolved project must never crash the tile, same
  // tolerance as TimerViewModel.resolveProject. Falls back to the raw id, not an error state.
  private suspend fun resolveProjectName(
    repository: ClockifyRepository,
    projectId: String,
  ): String? =
    when (val result = repository.projects()) {
      is ClockifyResult.Success -> result.value.firstOrNull { it.id == projectId }?.name
      is ClockifyResult.Failure -> null
    }

  /**
   * Minimal settable [ListenableFuture]: this module only has Guava's API-only `listenablefuture`
   * artifact on its classpath (confirmed via `:wear:dependencies`), not the full Guava library that
   * ships [com.google.common.util.concurrent.SettableFuture], so bridging a suspend result into
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
