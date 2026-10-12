package fi.nikosavola.clockifywear.tile

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Minimal settable [ListenableFuture]: this module only has Guava's API-only `listenablefuture`
 * artifact on its classpath, not the full Guava library that ships
 * [com.google.common.util.concurrent.SettableFuture], so bridging a suspend result into
 * TileService's Future-based contract needs a small local implementation instead. Backed by
 * [CompletableFuture] rather than reinventing listener bookkeeping.
 */
internal class CompletableListenableFuture<T> : ListenableFuture<T> {
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
