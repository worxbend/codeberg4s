package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.syntax.discard

import sttp.capabilities.Effect
import sttp.client4.Backend
import sttp.client4.GenericRequest
import sttp.client4.Response
import sttp.monad.MonadError

import scala.concurrent.Future

import java.util.concurrent.atomic.AtomicInteger

/** A backend that delegates everything and counts how often it was closed.
  *
  * Resource ownership is the one part of [[CodebergClient.close]] that cannot be checked by looking at a response:
  * whether a backend was closed is only observable from the backend. Wrapping a `BackendStub` rather than replacing it
  * keeps the request path unchanged, so a test can assert both the answer and the ownership in one run.
  *
  * @param delegate
  *   the stub that actually answers requests
  */
final class CountingBackend(delegate: Backend[Future]) extends Backend[Future]:

  private val closeCalls: AtomicInteger = AtomicInteger(0)

  override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
    delegate.send(request)

  override def close(): Future[Unit] =
    closeCalls.incrementAndGet().discard
    delegate.close()

  override def monad: MonadError[Future] = delegate.monad

  /** How many times `close()` has been called. */
  def closes: Int = closeCalls.get()
