package munit.internal

import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Task

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.Awaitable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

object PlatformCompat {
  private val sh = Executors
    .newSingleThreadScheduledExecutor(new ThreadFactory {
      val counter = new AtomicInteger
      def threadNumber() = counter.incrementAndGet()
      def newThread(r: Runnable) =
        new Thread(r, s"munit-scheduler-${threadNumber()}") {
          setDaemon(true)
          setPriority(Thread.NORM_PRIORITY)
        }
    })

  def awaitResult[T](awaitable: Awaitable[T]): T = Await
    .result(awaitable, Duration.Inf)

  def executeAsync(
      task: Task,
      eventHandler: EventHandler,
      loggers: Array[Logger],
  ): Future[Unit] = {
    task.execute(eventHandler, loggers)
    Future.successful(())
  }

  def waitAtMost[T](
      startFuture: () => Future[T],
      duration: Duration,
      ec: ExecutionContext,
  ): Future[T] = {
    val onComplete = Promise[T]()
    val timeout =
      if (duration.isFinite) Some(sh.schedule[Unit](
        () =>
          onComplete
            .tryFailure(new TimeoutException(s"test timed out after $duration")),
        duration.toMillis,
        TimeUnit.MILLISECONDS,
      ))
      else None
    ec.execute(new Runnable {
      def run(): Unit = startFuture().onComplete { result =>
        onComplete.tryComplete(result)
        timeout.foreach(_.cancel(false))
      }(ec)
    })
    onComplete.future
  }

  def setTimeout(ms: Int)(body: => Unit): () => Unit = {
    val scheduled = sh.schedule[Unit](() => body, ms, TimeUnit.MILLISECONDS)

    () => scheduled.cancel(false)
  }

  def isIgnoreSuite(cls: Class[_]): Boolean = cls
    .getAnnotationsByType(classOf[munit.IgnoreSuite]).nonEmpty
  def isJVM: Boolean = true
  def isJS: Boolean = false
  def isNative: Boolean = false
  def getThisClassLoader: ClassLoader = this.getClass().getClassLoader()

  type InvocationTargetException = java.lang.reflect.InvocationTargetException
  type UndeclaredThrowableException =
    java.lang.reflect.UndeclaredThrowableException
}
