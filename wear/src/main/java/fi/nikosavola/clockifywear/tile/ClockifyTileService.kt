package fi.nikosavola.clockifywear.tile

import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import fi.nikosavola.clockifywear.ClockifyApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The swipeable Tile surface showing the current running timer at a glance. Content itself is
 * [TileContentBuilder]'s job; this class is only the Service contract around it: the Future-based
 * callbacks, the coroutine that fills them, and the scope lifetime.
 */
class ClockifyTileService : TileService() {
  // SupervisorJob: an exception from one onTileRequest's coroutine must not cancel this scope's
  // Job and silently take every subsequent tile refresh down with it.
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // One instance per live service: TileContentBuilder owns the TileClickResolver whose Mutex only
  // serializes concurrent onTileRequest calls against this same bound service - see
  // TileClickResolver's doc comment.
  private val contentBuilder = TileContentBuilder(this)

  override fun onTileRequest(
    requestParams: RequestBuilders.TileRequest
  ): ListenableFuture<TileBuilders.Tile> {
    val future = CompletableListenableFuture<TileBuilders.Tile>()
    val appContainer = (applicationContext as ClockifyApp).appContainer
    val lastClickableId = requestParams.currentState.lastClickableId
    val deviceConfiguration = requestParams.deviceConfiguration
    // LAZY, plus wiring future.onCancel before starting: guarantees the Job is assigned before the
    // coroutine body can run, so a cancel() racing the launch can never see a null job. Same
    // ordering reasoning as TimerViewModel.start()'s use of LAZY.
    val job =
      serviceScope.launch(start = CoroutineStart.LAZY) {
        try {
          future.set(
            contentBuilder.build(
              deviceConfiguration = deviceConfiguration,
              lastClickableId = lastClickableId,
              repository = appContainer.repository,
              settingsStore = appContainer.settingsStore,
            )
          )
        } catch (e: CancellationException) {
          throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
          // Deliberately generic: build can throw from OkHttp, from the protolayout-material3
          // builders, or from anything else in its call graph, and any of those must complete the
          // future exceptionally rather than escape this launch and crash the whole app process
          // from a background tile refresh.
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
    future.set(contentBuilder.resources())
    return future
  }
}
