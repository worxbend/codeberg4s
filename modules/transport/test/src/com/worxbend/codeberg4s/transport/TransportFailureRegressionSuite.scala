package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.auth.{ApiToken, Auth, Password}
import com.worxbend.codeberg4s.core.{CodebergRequest, Timer, TransportFailure}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{BaseUri, CodebergConfig, HttpMethod, TransportCause, ValidationError}

import sttp.capabilities.{Effect, StreamMaxLengthExceededException}
import sttp.client4.testing.{BackendStub, RecordingBackend}
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.{Backend, GenericRequest, Response}

import munit.FunSuite

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.ControlThrowable
import scala.util.{Failure, Try}

import java.io.IOException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutionException, RejectedExecutionException}
import javax.net.ssl.SSLException

/** No sockets or real clocks: exercise the adapter, not BackendStub's exception rewriting. */
final class TransportFailureRegressionSuite extends FunSuite:
  private given ExecutionContext = ExecutionContext.parasitic

  private val config              = CodebergConfig(Auth.Anonymous).copy(baseUri = BaseUri.Codeberg)
  private val request             = CodebergRequest("regression", HttpMethod.Get, List("probe"), Nil, Nil, None)
  private val idle: Timer[Future] = new Timer[Future]:
    override def nowMillis: Future[Long]                       = Future.successful(0L)
    override def sleep(duration: FiniteDuration): Future[Unit] = Future.never

  private val specialized: List[(String, () => Throwable, TransportCause)] = List(
    ("TLS", ()       => SSLException("nested TLS"), TransportCause.Tls("nested TLS")),
    ("DNS", ()       => UnknownHostException("nested DNS"), TransportCause.Dns("nested DNS")),
    (
      "size",
      ()             => StreamMaxLengthExceededException(64L),
      TransportCause.ResponseTooLarge("Stream length limit of 64 bytes exceeded")
    ),
    ("interrupt", () => InterruptedException("nested interrupt"), TransportCause.Interrupted("nested interrupt")),
  )

  for (name, makeError, expected) <- specialized do
    for synchronous <- List(false, true) do
      test(s"${mode(synchronous)} generic I/O wrapper preserves nested $name diagnosis"):
        val error      = IOException("outer I/O", RuntimeException("middle", makeError()))
        val port       = SttpHttpPort(rawFailure(error, synchronous), config, idle)
        val invocation = Try(port.send(request, "redacted", 64L))
        assert(invocation.isSuccess, s"send threw synchronously: $invocation")
        val result     = Await.result(invocation.get, 3.seconds)
        assertEquals(result, Left(TransportFailure(expected)))

  for synchronous <- List(false, true) do
    test(s"${mode(synchronous)} ordinary backend throw uses the same transport rail"):
      val port       = SttpHttpPort(rawFailure(IllegalStateException("backend defect"), synchronous), config, idle)
      val invocation = Try(port.send(request, "redacted", 64L))
      assert(invocation.isSuccess, s"send threw synchronously: $invocation")
      val result     = Await.result(invocation.get, 3.seconds)
      assertEquals(result, Left(TransportFailure(TransportCause.Unknown("backend defect"))))

    test(s"${mode(synchronous)} multi-node cause cycle terminates within bounded traversal"):
      val visits     = AtomicInteger()
      // A bounded tripwire makes the old recursive classifier fail rather than
      // spin forever on the parasitic EC. It is not an expected production cause.
      val first      = CycleNode("cycle-a", visits)
      val second     = CycleNode("cycle-b", visits)
      first.initCause(second).discard
      second.initCause(first).discard
      val port       = SttpHttpPort(rawFailure(first, synchronous), config, idle)
      val invocation = Try(port.send(request, "redacted", 64L))
      assert(invocation.isSuccess, s"send threw synchronously: $invocation")
      val completed  = Await.ready(invocation.get, 3.seconds).value.get
      assert(completed.isSuccess, s"cycle escaped the transport rail: $completed")
      assert(completed.get.left.toOption.exists(_.cause.isInstanceOf[TransportCause.Unknown]))
      assert(visits.get() <= 8, s"cause cycle exceeded the traversal budget: ${visits.get()} visits")

  private val fatals: List[(String, () => Throwable)] = List(
    ("VM", ()      => SyntheticVmError()),
    ("linkage", () => LinkageError("synthetic linkage failure")),
    ("control", () => SyntheticControl()),
  )

  for (name, makeFatal) <- fatals do
    for synchronous <- List(false, true) do
      test(s"${mode(synchronous)} boxed synthetic $name failure stays outside the transport rail"):
        val fatal      = makeFatal()
        // Future boxes fatal failures; generic I/O ancestors must not hide them.
        val boxed      = ExecutionException(IOException("outer I/O", fatal))
        val port       = SttpHttpPort(rawFailure(boxed, synchronous), config, idle)
        val invocation = Try(port.send(request, "redacted", 64L))
        assert(invocation.isSuccess, s"send threw the wrapper synchronously: $invocation")
        val completed  = Await.ready(invocation.get, 3.seconds).value.get
        completed match
          case Failure(error) => assert(error eq boxed, "the original boxed failure identity was lost")
          case other          => fail(s"fatal was recovered into a transport result: $other")

  for synchronous <- List(false, true) do
    test(s"${mode(synchronous)} refused required deadline fails closed before backend admission"):
      val refused              = RejectedExecutionException("deadline refused")
      val timer: Timer[Future] = new Timer[Future]:
        override def nowMillis: Future[Long]                       = Future.successful(0L)
        override def sleep(duration: FiniteDuration): Future[Unit] =
          if synchronous then throw refused else Future.failed(refused)
      val backend              = RecordingBackend(BackendStub.asynchronousFuture.whenAnyRequest.thenRespondOk())
      val port                 = SttpHttpPort(backend, config, timer)
      val invocation           = Try(port.send(request, "redacted", 64L))
      assert(invocation.isSuccess, s"deadline refusal escaped synchronously: $invocation")
      val completed            = Await.ready(invocation.get, 3.seconds).value.get
      assertEquals(completed, Failure(refused))
      assertEquals(backend.allInteractions.size, 0, "backend admitted a call without a required deadline")

  test("a timer closed before send settles even when the injected backend would never answer"):
    val closed               = RejectedExecutionException("timer already closed")
    val timer: Timer[Future] = new Timer[Future]:
      override def nowMillis: Future[Long]                       = Future.successful(0L)
      override def sleep(duration: FiniteDuration): Future[Unit] = Future.failed(closed)
    val backend              = BackendStub.asynchronousFuture.whenAnyRequest.thenRespondF(Future.never)
    val pending              = SttpHttpPort(backend, config, timer).send(request, "redacted", 64L)
    val completed            = Await.ready(pending, 3.seconds).value
    assertEquals(completed, Some(Failure(closed)), "required timer refusal left the call pending")

  for failSend <- List(false, true) do
    test(s"injected backend ownership remains with caller when send failure=$failSend"):
      val closes   = AtomicInteger()
      val delegate = BackendStub.asynchronousFuture.whenAnyRequest.thenRespondOk()
      val backend  = new DelegateBackend[Future, Any](delegate) with Backend[Future]:
        override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
          if failSend then Future.failed(IOException("synthetic I/O")) else delegate.send(request)
        override def close(): Future[Unit]                                                          =
          closes.incrementAndGet().discard
          delegate.close()
      val result   = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 64L), 3.seconds)
      assertEquals(result.isLeft, failSend)
      assertEquals(closes.get(), 0, "transport closed a caller-owned backend")
      Await.result(backend.close(), 3.seconds)
      assertEquals(closes.get(), 1)

  test("failure of an already armed deadline settles a pending send as a failed effect"):
    val deadline             = Promise[Unit]()
    val timer: Timer[Future] = new Timer[Future]:
      override def nowMillis: Future[Long]                       = Future.successful(0L)
      override def sleep(duration: FiniteDuration): Future[Unit] = deadline.future
    val backend              = new DelegateBackend[Future, Any](BackendStub.asynchronousFuture) with Backend[Future]:
      override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] = Future.never
    val pending              = SttpHttpPort(backend, config, timer).send(request, "redacted", 64L)
    assert(!pending.isCompleted)
    val closed               = RejectedExecutionException("timer closed while send pending")
    deadline.failure(closed).discard
    val completed            = Await.ready(pending, 3.seconds).value
    assertEquals(completed, Some(Failure(closed)))

  for (logical, native) <- List(
                             1.nanosecond                                   -> 1.millisecond,
                             999.microseconds                               -> 1.millisecond,
                             1500.microseconds                              -> 2.milliseconds,
                             (Long.MaxValue - 1.second.toNanos).nanoseconds ->
                               ((Long.MaxValue - 1.second.toNanos) / 1000000L + 1L).milliseconds,
                             (Long.MaxValue / 1000000L).milliseconds        -> (Long.MaxValue / 1000000L).milliseconds,
                           )
  do
    test(s"native read timeout rounds $logical upward while logical deadline remains exact"):
      val durations            = scala.collection.mutable.ListBuffer.empty[FiniteDuration]
      val timer: Timer[Future] = new Timer[Future]:
        override def nowMillis: Future[Long]                       = Future.successful(0L)
        override def sleep(duration: FiniteDuration): Future[Unit] =
          durations.append(duration)
          Future.never
      val backend              = RecordingBackend(BackendStub.asynchronousFuture.whenAnyRequest.thenRespondOk())
      val port                 = SttpHttpPort(backend, config.copy(readTimeout = logical), timer)
      val result               = Await.result(port.send(request, "redacted", 64L), 3.seconds)
      assertEquals(result.map(_.status), Right(200))
      assertEquals(backend.allInteractions.head._1.options.readTimeout, native)
      assertEquals(durations.toList, List(logical))

  for synchronous <- List(false, true) do
    test(s"${mode(synchronous)} competing specific categories retain outermost specific precedence"):
      val error  = IOException("generic", SSLException("outer TLS", UnknownHostException("inner DNS")))
      val result = Await.result(
        SttpHttpPort(rawFailure(error, synchronous), config, idle).send(request, "redacted", 64L),
        3.seconds
      )
      assertEquals(result, Left(TransportFailure(TransportCause.Tls("outer TLS"))))

    test(s"${mode(synchronous)} cause budget exhaustion never hides a deeper boxed fatal"):
      val error   = (1 to 80).foldLeft[Throwable](SyntheticVmError()): (nested, _) =>
        IOException("generic wrapper", nested)
      val boxed   = ExecutionException(error)
      val pending = SttpHttpPort(rawFailure(boxed, synchronous), config, idle).send(request, "redacted", 64L)
      assertEquals(Await.ready(pending, 3.seconds).value, Some(Failure(boxed)))

  private val echoToken    = "transport-only-configured-token"
  private val echoPassword = " transport-only-pässwörd-雪 "
  private val echoUsername = "transport-üser"
  private val echoWire = Base64.getEncoder.encodeToString(s"$echoUsername:$echoPassword".getBytes(StandardCharsets.UTF_8))
  private val echoBasic       = valid(Auth.basic(echoUsername, valid(Password.from(echoPassword))))
  private val echoCredentials = List(
    ("token", Auth.Token(valid(ApiToken.from(echoToken))), echoToken),
    ("Basic password", echoBasic, echoPassword),
    ("Basic wire", echoBasic, echoWire),
  )

  for (name, auth, secret) <- echoCredentials do
    for synchronous <- List(false, true) do
      for boundary <- List(false, true) do
        test(s"${mode(synchronous)} $name detail is sanitized before trim and clipping, boundary=$boundary"):
          val prefix  = if boundary then "ordinary ".padTo(SttpHttpPort.MaxDetailLength - 4, 'x') else "ordinary "
          // The raw password's surrounding spaces must survive until sanitizing;
          // in the short case it is at the end, where pre-redaction trim leaks it.
          val message = if boundary then s"$prefix$secret trailing text" else s"$prefix$secret"
          val port    = SttpHttpPort(rawFailure(IOException(message), synchronous), config.copy(auth = auth), idle)
          val result  = Await.result(port.send(request, "redacted", 64L), 3.seconds)
          result match
            case Left(TransportFailure(TransportCause.ConnectionFailed(detail))) =>
              assert(detail.startsWith(prefix), "ordinary diagnostic prefix was lost")
              assert(!detail.contains(secret), "configured credential survived detail redaction")
              assert(!detail.contains(secret.trim.take(4)), "a clipped/trimmed credential prefix survived")
            case other => fail(s"expected scrubbed I/O failure, got $other")

  test("native rounding at the FiniteDuration representability edge stays finite and positive"):
    val native = SttpHttpPort.nativeReadTimeout(Long.MaxValue.nanoseconds)
    assertEquals(native.toMillis, Long.MaxValue / 1000000L)
    assert(native.toNanos > 0L)

  private def valid[A](value: Either[ValidationError, A]): A = value match
    case Right(result) => result
    case Left(error)   => fail(s"invalid synthetic fixture: $error")

  private def mode(synchronous: Boolean): String = if synchronous then "synchronous" else "asynchronous"

  private def rawFailure(error: Throwable, synchronous: Boolean): Backend[Future] =
    new DelegateBackend[Future, Any](BackendStub.asynchronousFuture) with Backend[Future]:
      override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
        if synchronous then throw error else Future.failed(error)

  private final class SyntheticVmError extends VirtualMachineError("synthetic VM failure")
  private final class SyntheticControl extends ControlThrowable

  private final class CycleNode(message: String, visits: AtomicInteger) extends RuntimeException(message):

    override def getCause: Throwable =
      if visits.incrementAndGet() > 8 then throw IllegalStateException("cause cycle traversal exceeded budget")
      super.getCause
