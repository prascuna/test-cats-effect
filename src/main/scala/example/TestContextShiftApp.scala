package example

import java.util.concurrent._
import java.util.function.{BiFunction, Supplier}

import cats.effect.{Concurrent, ContextShift, ExitCode, IO, IOApp}
import example.Implicits._

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.language.higherKinds

object TestContextShiftApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val res = for {
      _ <- IO.delay(debug("XXX - one"))
      _ <- ScalaService.doSomething
      _ <- IO.delay(debug("XXX - two"))
      _ <- Concurrent.delayCompletableFuture[IO, Unit](JavaService.doSomething)
      _ <- IO.delay(debug("XXX - three"))
      _ <- Concurrent.delayCompletableFuture[IO, Unit](
        JavaService.doSomethingWithBody { () =>
          debug("Body")
        }
      )
      _ <- IO.delay(debug("XXX - four"))
    } yield ()

    res.map(_ => ExitCode.Success)
  }

}

object ScalaService {
  private val executionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(3, new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r)
          t.setName(s"scala-service-${t.getId}")
          t
        }
      })
    )

  def doSomething(implicit contextShift: ContextShift[IO]): IO[Unit] =
    contextShift.evalOn(executionContext) {
      IO.delay(debug("ScalaService.doSomething"))
    }
}

object JavaService {
  private val executorService =
    Executors.newFixedThreadPool(3, new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r)
        t.setName(s"java-service-${t.getId}")
        t
      }
    })

  def doSomething: CompletableFuture[Unit] = {
    CompletableFuture.supplyAsync(new Supplier[Unit] {
      override def get(): Unit = debug("JavaService.doSomething")
    }, executorService)
  }
  def doSomethingWithBody(body: () => Unit): CompletableFuture[Unit] = {
    CompletableFuture.supplyAsync(new Supplier[Unit] {
      override def get(): Unit = {
        debug("JavaService.doSomethingWithBody")
        body()
      }
    }, executorService)
  }
}

object Implicits {
  def debug(message: String) =
    println(s"[${Thread.currentThread().getName}] $message")

  implicit class ConcurrentOps(private val concurrent: Concurrent.type)
      extends AnyVal {

    def delayCompletableFuture[F[_]: Concurrent, T](
      mkCompletableFuture: => CompletableFuture[T]
    ): F[T] = {
      debug("delayCompletableFuture: CompletableFuture => F[T]")
      Concurrent[F]
        .cancelable[T] { cb =>
          val completableFuture = mkCompletableFuture
          debug("delayCompletableFuture: Handling callback")
          completableFuture.handle[Unit](new BiFunction[T, Throwable, Unit] {
            override def apply(result: T, err: Throwable): Unit = {
              debug("delayCompletableFuture: apply")
              err match {
                case null =>
                  cb(Right(result))
                case _: CancellationException =>
                  ()
                case ex: CompletionException if ex.getCause ne null =>
                  cb(Left(ex.getCause))
                case ex =>
                  cb(Left(ex))
              }
            }
          })

          Concurrent[F].delay(completableFuture.cancel(true))
        }
    }
  }
}
