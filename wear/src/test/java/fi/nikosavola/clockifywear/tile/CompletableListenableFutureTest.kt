package fi.nikosavola.clockifywear.tile

import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract [ClockifyTileService] relies on: a value or an exception completes it, and
 * cancelling it runs the hook that stops the coroutine doing the work.
 */
class CompletableListenableFutureTest {
  @Test
  fun `a set value is returned and reported as done`() {
    val future = CompletableListenableFuture<String>()

    future.set("tile")

    assertTrue(future.isDone)
    assertFalse(future.isCancelled)
    assertEquals("tile", future.get(1, TimeUnit.SECONDS))
  }

  @Test
  fun `an exception surfaces from get rather than being swallowed`() {
    val future = CompletableListenableFuture<String>()
    val failure = IllegalStateException("tile build failed")

    future.setException(failure)

    assertTrue(future.isDone)
    assertEquals(failure, assertThrows(Exception::class.java) { future.get() }.cause)
  }

  @Test
  fun `cancelling runs the hook that stops the work behind it`() {
    val future = CompletableListenableFuture<String>()
    var cancelled = false
    future.onCancel = { cancelled = true }

    assertTrue(future.cancel(true))

    assertTrue(cancelled)
    assertTrue(future.isCancelled)
  }

  @Test
  fun `a listener added before completion runs when the value arrives`() {
    val future = CompletableListenableFuture<String>()
    var notified = false
    future.addListener({ notified = true }, DirectExecutor)

    future.set("tile")

    assertTrue(notified)
  }

  // The listener must run on the exceptional path too: the host binding this service is waiting on
  // exactly that signal.
  @Test
  fun `a listener also runs when the future completes exceptionally`() {
    val future = CompletableListenableFuture<String>()
    var notified = false
    future.addListener({ notified = true }, DirectExecutor)

    future.setException(IllegalStateException("boom"))

    assertTrue(notified)
  }

  private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
  }
}
