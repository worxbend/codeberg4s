package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.{
  CodebergRequest,
  CodebergResponse,
  DiagnosticRedaction,
  FailureCauses,
  HttpPort,
  RequestBody,
  ResponseBody,
  Timer,
  TransportFailure
}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{CodebergConfig, ContentType, TransportCause}

import sttp.capabilities.{Effect, StreamMaxLengthExceededException}
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.{
  Backend,
  BackendOptions,
  GenericRequest,
  PartialRequest,
  Request,
  Response,
  asInputStreamAlwaysUnsafe,
  basicRequest,
  multipart
}
import sttp.model.{Header, HeaderNames, MediaType, Method, Uri}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import java.io.IOException
import java.net.http.{HttpClient, HttpTimeoutException}
import java.net.{Authenticator, PasswordAuthentication, SocketTimeoutException, UnknownHostException}
import java.util.Locale
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Executor, Executors, RejectedExecutionException, TimeoutException}

/** The sttp implementation of [[com.worxbend.codeberg4s.core.HttpPort]] for `Future`.
  *
  * '''Failures.''' Every HTTP status, including 3xx/5xx, is a response; status interpretation belongs to the pipeline.
  * A transport failure means no complete response, not proof the server received nothing or made no mutation.
  * Synchronous and asynchronous nonfatal backend failures share one adapter boundary. Specific causes precede generic
  * I/O ancestors; identity-based bounded traversal terminates cycles and preserves boxed fatal failures as failed
  * effects. Direct fatal throws remain fatal. Unknown defects are not treated as retryable network failures.
  *
  * '''Security.''' Nothing logs. Configured auth echoes are scrubbed before exception-detail clipping; arbitrary
  * backend text is not assumed safe. Sensitive-operation diagnostic withholding belongs to the pipeline. Caller
  * Authorization/Proxy-Authorization headers are removed and configured auth applied last. Automatic redirects are
  * disabled at the sttp request level as well as the native JDK level, including same-origin reads and downloads: 3xx
  * is returned without replaying its method/body. Explicit facade handling remains a pipeline/client concern.
  *
  * '''Ownership.''' This port never closes the backend, including on failure. defaultBackend owns a native client and a
  * separate daemon body-I/O executor; it starts orderly native shutdown on close and shuts its I/O lane down after
  * admitted physical sends settle. No caller execution context is closed. Release acknowledgement is not drainage.
  *
  * '''Timeouts.''' The required timer deadline is armed before backend admission and bounds the exchange through the
  * last body byte. Already refused/elapsed deadlines do not admit a send. Deadline failure settles the logical wrapper
  * as the original failed Future, not a retryable TransportCause. Small completion callbacks run parasitically,
  * independently of the caller pool. The original logical duration is exact; the native header timeout is rounded up to
  * millisecond resolution (saturated at FiniteDuration's extreme representability edge). Native timeouts can abort a
  * header wait, but the logical timeout '''abandons, does not cancel''' the underlying Future/body read. An abandoned
  * read retains its connection and owned I/O worker until eventual reader exit. Completed sends also leave their unused
  * timer sleep armed until it elapses: Timer has no cancellation capability. Connect timeout is backend-wide and
  * therefore applies only to defaultBackend or a correctly configured injection. This is a per-exchange bound, not a
  * total call/walk deadline or aggregate outstanding-send budget.
  *
  * '''Response size.''' Every status uses InputStream acquisition after decompression and a response-description map.
  * The reader owns decoded-stream closure, avoiding a native callback finalizer that could replace a primary failure.
  * The incremental decoded-byte reader consumes at most maxBodyBytes plus one probe byte before allocation can pass the
  * bound; no peer Content-Length or configured Long is narrowed into an allocation size. The decoded reader is
  * authoritative: pinned sttp's wire-byte guard rejects exact-bound EOF probes and narrows large Long bounds unsafely,
  * so it is not installed. Streams close on success, read/limit failure and eventual late completion, not when a
  * logical timeout wins a blocked read. Native sttp also cleans up acquisition/decompression failures. Bytes and
  * declared charset are preserved for ResponseBody's lazy text view. Caller backends must support
  * asInputStreamAlwaysUnsafe with response mapping and obey request options; their native scheduling, decompression and
  * redirect implementation remain their responsibility. This port neither replaces nor closes them.
  *
  * @param backend
  *   the caller-owned sttp backend, supporting mapped unsafe InputStream responses
  * @param config
  *   endpoint, configured auth, user agent and exact logical read timeout
  * @param timer
  *   the required scheduler for the logical whole-exchange deadline
  */
final class SttpHttpPort(
    backend: Backend[Future],
    config: CodebergConfig,
    timer: Timer[Future],
)(using @scala.annotation.unused executionContext: ExecutionContext)
    extends HttpPort[Future]:

  private val redaction: DiagnosticRedaction = DiagnosticRedaction(config.auth)

  /** The request target root, parsed once.
    *
    * [[com.worxbend.codeberg4s.BaseUri]] validates the scheme and the authority, so a `Left` here means sttp rejected
    * something that validation allowed. It is reported as a transport failure rather than thrown, and the offending
    * value is not echoed because a base URI may carry user information.
    */
  private val root: Either[TransportFailure, Uri] =
    Uri
      .parse(config.baseUri.value)
      .left
      .map(_ => TransportFailure(TransportCause.Unknown(SttpHttpPort.UnparseableBaseUri)))

  /** The part of an sttp request that is the same for every request this port will ever send.
    *
    * An sttp request is an immutable value, so each builder call allocates a new one. The user agent and the read
    * timeout are read straight off [[com.worxbend.codeberg4s.CodebergConfig]], which cannot change once the port
    * exists, so applying them per request rebuilt the identical pair of values on every call. They are applied once
    * here instead and [[build]] starts from the result.
    *
    * The user agent sits here even though configuration has to win over a caller who sets `User-Agent` — normally that
    * would mean applying it last, since sttp's `header` replaces by default. It does not need to: `User-Agent` is one
    * of the names [[SttpHttpPort.callerHeaders]] drops, so by the time the caller's headers are applied there is
    * nothing left that could overwrite this one.
    */
  private val template: PartialRequest[Either[String, String]] =
    basicRequest
      .header(HeaderNames.UserAgent, config.userAgent.value)
      .readTimeout(SttpHttpPort.nativeReadTimeout(config.readTimeout))
      .followRedirects(false)

  /** Adapts nonfatal synchronous failures to the same rail as failed Futures; direct fatal throws remain fatal.
    * `redactedUri` is deliberately unused because the adapter reports no request URI and never logs.
    */
  override def send(
      request: CodebergRequest,
      redactedUri: String,
      maxBodyBytes: Long,
  ): Future[Either[TransportFailure, CodebergResponse]] =
    bounded(dispatch(request, maxBodyBytes))

  /** Deliberately opaque: this object holds the configured credentials, so it renders nothing about its state. */
  override def toString: String = "SttpHttpPort"

  /** Guard request construction and backend invocation, then adapt their outcome once. Preflight refusals never dial. */
  private def dispatch(
      request: CodebergRequest,
      maxBodyBytes: Long,
  ): Future[Either[TransportFailure, CodebergResponse]] =
    val sending = SttpHttpPort.guarded:
      root.flatMap(uri => build(request, uri, maxBodyBytes)) match
        case Left(failure) => Future.successful(Left(failure))
        case Right(built)  => built.send(backend).map(SttpHttpPort.succeed)(using ExecutionContext.parasitic)
    sending.transform(result => SttpHttpPort.adapt(result, redaction))(using ExecutionContext.parasitic)

  /** Required deadlines are armed before admission. Timeout abandons the physical Future; failure propagates as a
    * failed effect. The tiny race callbacks never wait on the caller execution context.
    */
  private def bounded[A](sending: => Future[Either[TransportFailure, A]]): Future[Either[TransportFailure, A]] =
    val outcome  = Promise[Either[TransportFailure, A]]()
    val deadline = SttpHttpPort.guarded(timer.sleep(config.readTimeout))
    deadline.onComplete {
      case Success(())    => outcome.trySuccess(Left(TransportFailure(SttpHttpPort.exchangeTimedOut(config)))).discard
      case Failure(error) => outcome.tryFailure(error).discard
    }(using ExecutionContext.parasitic)

    // A refused (or already elapsed) deadline cannot admit an unbounded exchange.
    // Refusal concurrent with admission can still abandon a physical Future; it
    // always settles the logical wrapper, and never becomes a retryable failure.
    if !outcome.isCompleted then
      sending.onComplete(result => outcome.tryComplete(result).discard)(using ExecutionContext.parasitic)
    outcome.future

  /** Builds the sttp request, reading '''every''' response body as bytes, or refuses to build it at all.
    *
    * Pinned sttp 4.0.26 interprets this response-description map inside the backend's body-I/O effect, not in a port
    * Future continuation. Unsafe acquisition transfers decoded-stream closure to the bounded reader; it does not leak a
    * stream to the caller. Unlike callback acquisition, it installs no second ordinary close finalizer that could
    * replace a refusal or read throwable. Native abnormal acquisition/decompression cleanup remains installed and
    * preserves the primary throwable. Every status uses this decoded allocation bound, without premature String
    * conversion or the pinned wire guard's exact-bound/Long narrowing defects.
    *
    * The `Left` comes from [[SttpHttpPort.withBody]], which refuses a body whose media type cannot be written as a
    * header.
    *
    * '''The order the headers go on is the security-relevant part.''' sttp's `header` defaults to
    * `DuplicateHeaderBehavior.Replace`, so a name written twice keeps the value written last. The order below is
    * therefore, from first to last: the user agent, from [[template]]; the body's own `Content-Type`, from
    * [[SttpHttpPort.withBody]]; the caller's headers, which is what lets `POST /markdown/raw` send a `text/plain`
    * content type over a body core models as JSON; and finally the credential, which nothing after it can overwrite
    * because there is nothing after it.
    */
  private def build(
      request: CodebergRequest,
      uri: Uri,
      maxBodyBytes: Long,
  ): Either[TransportFailure, Request[Either[TransportFailure, Array[Byte]]]] =
    SttpHttpPort
      .withBody(request.body, template)
      .map: carrying =>
        withAuth(carrying.headers(SttpHttpPort.callerHeaders(request.headers)*))
          .method(Method(request.method.wireName), SttpHttpPort.target(uri, request))
          .response(asInputStreamAlwaysUnsafe.map(stream => BoundedResponseBody.read(stream, maxBodyBytes)))

  /** The sanctioned wire-header call site of reveal. Configured auth goes on last so it cannot be overwritten. */
  private def withAuth(request: PartialRequest[Either[String, String]]): PartialRequest[Either[String, String]] =
    config.auth match
      case Auth.Anonymous                 => request
      case Auth.Token(token)              => request.header(HeaderNames.Authorization, s"token ${token.reveal}")
      case Auth.Basic(username, password) => request.auth.basic(username, password.reveal)

/** Construction of the backend [[SttpHttpPort]] sends on, and the pure parts of the adapter. */
object SttpHttpPort:

  /** The detail reported when sttp cannot parse the configured base URI. Never echoes the value. */
  val UnparseableBaseUri: String = "the configured base URI is not a valid request target"

  /** The detail reported when a multipart part's media type cannot be written as a header. Never echoes the value.
    *
    * Reaching this means a [[com.worxbend.codeberg4s.core.RequestBody.Multipart]] was built from a raw string rather
    * than from one of the upload commands, which already refuse a blank or control-carrying media type. The request is
    * not sent either way.
    */
  val UnsafeMultipartMediaType: String = "the multipart part's media type is blank or contains a control character"

  /** The longest exception message copied into a [[com.worxbend.codeberg4s.TransportCause]].
    *
    * A misbehaving proxy can fail with a multi-kilobyte message, and a transport detail is meant to fit in a log line.
    */
  val MaxDetailLength: Int = 200

  /** The cause reported when the whole exchange — headers and body — did not finish within `config.readTimeout`.
    *
    * Internal, so a test can assert the exact value; the detail names the bound that fired and nothing about the
    * request, because a transport detail may end up in a log line.
    */
  private[codeberg4s] def exchangeTimedOut(config: CodebergConfig): TransportCause =
    TransportCause.Timeout(s"no complete response within the read timeout of ${config.readTimeout}")

  /** A caller-owned JDK Future backend with the default connect timeout and a private daemon body-I/O lane. The caller
    * must close it; no caller execution context is used for body work or shut down.
    */
  def defaultBackend(): Backend[Future] =
    defaultBackend(CodebergConfig.DefaultConnectTimeout, ExecutionContext.global)

  /** A caller-owned JDK Future backend, isolated from application continuation workers.
    *
    * Native client/executor and sttp body-I/O executor are separate owned resources. The latter is a cached daemon
    * pool, not an aggregate concurrency limit. Failed construction releases both. Proxy settings/authentication follow
    * BackendOptions.Default; native redirects are NEVER, with request-level suppression in the port as well. sttp
    * usingClient is non-owning, so this wrapper explicitly starts native shutdown instead of trusting its close.
    *
    * Close is prompt/idempotent. The I/O lane remains accepting until admitted physical sttp Futures settle, including
    * reads abandoned by logical timeout, then shuts down orderly. A permanently stalled physical read can keep a daemon
    * alive indefinitely; release acknowledgement does not promise cancellation or drainage.
    *
    * @param connectTimeout
    *   backend-wide native connection timeout
    * @param executionContext
    *   supplies the error reporter only; never used for native/body work or shut down
    */
  def defaultBackend(connectTimeout: FiniteDuration, executionContext: ExecutionContext): Backend[Future] =
    val io          = ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).name("codeberg4s-http-io-", 0L).factory()),
      executionContext.reportFailure,
    )
    val transferred = AtomicBoolean(false)
    try
      // The native client owns its own executor; sttp's blocking body callbacks
      // use our separate daemon lane, never the application's continuation pool.
      val client = configuredHttpClient(connectTimeout, None)
      try
        val backend = OwnedClientBackend(HttpClientFutureBackend.usingClient(client)(using io), client, Some(io))
        transferred.set(true)
        backend
      finally if !transferred.get() then client.shutdown()
    finally if !transferred.get() then io.shutdown()

  /** Internal lifecycle-test seam with an explicit caller executor. Production defaultBackend gives the native client
    * its own executor; this seam lets tests hold the client and observe native termination.
    */
  private[codeberg4s] def defaultHttpClient(
      connectTimeout: FiniteDuration,
      executionContext: ExecutionContext,
  ): HttpClient =
    val executor = executionContext match
      case value: Executor => Some(value)
      case _               => None
    configuredHttpClient(connectTimeout, executor)

  private def configuredHttpClient(connectTimeout: FiniteDuration, executor: Option[Executor]): HttpClient =
    val configured = HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(connectTimeout.toJava)
    val executing  = executor.fold(configured)(configured.executor)
    BackendOptions.Default.proxy.fold(executing)(proxy => proxied(executing, proxy)).build()

  /** Internal lifecycle-test seam: closes the native client, never its caller-owned executor. */
  private[codeberg4s] def owning(client: HttpClient, executionContext: ExecutionContext): Backend[Future] =
    OwnedClientBackend(HttpClientFutureBackend.usingClient(client)(using executionContext), client, None)

  /** Points the builder at a proxy, and answers that proxy's authentication challenge when it has credentials. */
  private def proxied(builder: HttpClient.Builder, proxy: BackendOptions.Proxy): HttpClient.Builder =
    val routed = builder.proxy(proxy.asJavaProxySelector)
    proxy.auth.fold(routed)(credentials => routed.authenticator(ProxyAuthenticator(credentials)))

  /** What to answer a `requestor` that is asking for credentials: the proxy's own, and only if it is the proxy asking.
    *
    * Internal because it is the decision [[ProxyAuthenticator]] exists to make, and a test can then assert it without
    * standing up a proxy. The `None` branch is the one that matters: a `java.net.Authenticator` is consulted for
    * origin-server challenges too, and answering one of those with the proxy's password would disclose it to whatever
    * host the request was aimed at.
    */
  private[codeberg4s] def proxyCredentialsFor(
      requestor: Authenticator.RequestorType,
      credentials: BackendOptions.ProxyAuth,
  ): Option[PasswordAuthentication] =
    requestor match
      case Authenticator.RequestorType.PROXY =>
        Some(PasswordAuthentication(credentials.username, credentials.password.toCharArray))
      case _                                 => None

  /** Owns native shutdown and optionally a private body-I/O lane. shutdown(), unlike JDK close(), does not wait for
    * in-flight reads. Keep the I/O lane until all admitted physical Futures settle so late acquisition/closure can
    * still run; never shut down a caller-owned executor. The JDK owns its separate default native executor.
    */
  private final class OwnedClientBackend(
      delegate: Backend[Future],
      client: HttpClient,
      io: Option[scala.concurrent.ExecutionContextExecutorService],
  ) extends DelegateBackend[Future, Any](delegate),
        Backend[Future]:

    private val closed: AtomicBoolean   = AtomicBoolean(false)
    private val pending: AtomicInteger  = AtomicInteger()
    private val released: Promise[Unit] = Promise[Unit]()

    override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
      // Register before checking close: close can shut the I/O lane down only
      // before admission or after the last admitted physical Future settles.
      pending.incrementAndGet().discard
      if closed.get() then
        finished()
        Future.failed(RejectedExecutionException("HTTP backend is closed"))
      else
        val attached = AtomicBoolean(false)
        try
          val sending = guarded(delegate.send(request))
          sending.onComplete(_ => finished())(using ExecutionContext.parasitic)
          attached.set(true)
          sending
        finally if !attached.get() then finished()

    override def close(): Future[Unit] =
      if closed.compareAndSet(false, true) then
        val closing = guarded:
          client.shutdown()
          delegate.close()
        closing.onComplete(result => released.tryComplete(result).discard)(using ExecutionContext.parasitic)
        releaseIdleIo()
      released.future

    private def finished(): Unit =
      pending.decrementAndGet().discard
      releaseIdleIo()

    private def releaseIdleIo(): Unit =
      if closed.get() && pending.get().equals(0) then io.foreach(_.shutdown())

  /** Hands [[proxyCredentialsFor]]'s answer to the JDK.
    *
    * `java.net.Authenticator`'s contract for "I hold no credentials for this challenge" is a `null` return, so `orNull`
    * here is the single point at which the `Option` that carries that answer meets the Java side.
    */
  private final class ProxyAuthenticator(proxyAuth: BackendOptions.ProxyAuth) extends Authenticator:

    override protected def getPasswordAuthentication: PasswordAuthentication =
      proxyCredentialsFor(getRequestorType, proxyAuth).orNull

  private def target(uri: Uri, request: CodebergRequest): Uri =
    uri.addPath(request.path).addParams(request.query*)

  /** The header names this port owns, lowercased so a lookup can be case-insensitive the way HTTP is.
    *
    * A header name is case-insensitive on the wire, so `authorization` and `Authorization` are one header and a set
    * membership test has to see them as one. `Locale.ROOT` rather than the default locale because the default one may
    * be Turkish, where lowercasing `I` produces a dotless `ı` and the comparison silently stops matching.
    */
  private val PortOwnedHeaders: Set[String] =
    Set(HeaderNames.Authorization, HeaderNames.ProxyAuthorization, HeaderNames.UserAgent)
      .map(_.toLowerCase(Locale.ROOT))

  /** The caller's headers with the ones this port owns removed, ready to hand to sttp.
    *
    * '''Why a credential header is dropped and not merely overwritten.'''
    * [[com.worxbend.codeberg4s.core.CodebergRequest]] documents that its `headers` never carry a credential, and every
    * one of those maps is built inside this library, so today none of them does. Nothing structural stops one: a new
    * endpoint is an ordinary `List[(String, String)]` away from putting `Authorization` there, and the result would be
    * a request authenticated as something other than what [[com.worxbend.codeberg4s.auth.Auth]] configured — or, with a
    * different spelling of the name, two credentials on one request. Removing the entry here makes that outcome
    * unreachable rather than unlikely, and costs nothing, because no endpoint has a reason to set these names.
    *
    * `User-Agent` is in the same set for a plainer reason: it is configuration too, and dropping it here is what lets
    * [[SttpHttpPort.template]] apply the configured one first and still win.
    *
    * Every other name is passed through untouched, which is the point — a per-request `Content-Type` is a legitimate
    * override and `POST /markdown/raw` depends on it.
    */
  private def callerHeaders(headers: List[(String, String)]): List[Header] =
    headers.collect:
      case (name, value) if !PortOwnedHeaders.contains(name.toLowerCase(Locale.ROOT)) => Header(name, value)

  /** Attaches the body, unless the body carries a media type that must not become a header.
    *
    * '''Defence in depth, and deliberately a second copy of a rule the domain already enforces.'''
    * [[com.worxbend.codeberg4s.issues.UploadAttachment.as]] and
    * [[com.worxbend.codeberg4s.repositories.publishing.UploadAsset.as]] refuse such a value at construction, and that
    * is the check a caller should ever see, because it names the field and happens before a request exists. This one
    * exists because [[com.worxbend.codeberg4s.core.RequestBody.Multipart]] is a plain case class that any code inside
    * the library can build with a bare `String`, and this method is the last point at which that string is still a
    * Scala value rather than wire bytes.
    *
    * A refusal is a [[TransportFailure]] and not an exception: a request that was never sent is exactly what
    * [[com.worxbend.codeberg4s.TransportCause]] describes, and the pipeline above already knows how to report one.
    */
  private def withBody(
      body: Option[RequestBody],
      request: PartialRequest[Either[String, String]],
  ): Either[TransportFailure, PartialRequest[Either[String, String]]] =
    body match
      case Some(RequestBody.Multipart(_, _, _, mediaType)) if !ContentType.isSafe(mediaType) =>
        Left(TransportFailure(TransportCause.Unknown(UnsafeMultipartMediaType)))
      case other => Right(attach(other, request))

  private def attach(
      body: Option[RequestBody],
      request: PartialRequest[Either[String, String]],
  ): PartialRequest[Either[String, String]] =
    body match
      case None                                       => request
      case Some(RequestBody.Empty)                    => request.body("")
      case Some(RequestBody.Json(value))              => request.body(value).contentType(MediaType.ApplicationJson)
      case Some(RequestBody.Text(value, mediaType))   => request.body(value).contentType(mediaType)
      case Some(RequestBody.Binary(bytes, mediaType)) => request.body(bytes).contentType(mediaType)
      case Some(RequestBody.Multipart(fieldName, fileName, bytes, mediaType)) =>
        // multipartBody sets Content-Type: multipart/form-data and picks the
        // boundary itself; setting it here as well would produce a header whose
        // boundary does not match the body sttp actually writes.
        request.multipartBody(multipart(fieldName, bytes).fileName(fileName).contentType(mediaType))

  /** The charset the response declared is captured here, next to the bytes, and applied nowhere yet.
    *
    * sttp used to make this decision inside `asStringAlways`; it now belongs to
    * [[com.worxbend.codeberg4s.core.ResponseBody]], which is where a reader that wants text asks for it. Reading the
    * header at this point rather than later matters because a `ResponseBody` outlives the sttp `Response` it came from.
    */
  private def succeed(
      response: Response[Either[TransportFailure, Array[Byte]]],
  ): Either[TransportFailure, CodebergResponse] =
    response.body.map: bytes =>
      val body = ResponseBody.of(bytes, ResponseBody.charsetOf(response.contentType))
      CodebergResponse(response.code.code, grouped(response.headers), body)

  /** Response headers keyed by the name they arrived under. A header the instance repeated keeps every value, in the
    * order it sent them. Codeberg sends names lowercase over HTTP/2 and mixed-case elsewhere; lowercasing them is
    * [[com.worxbend.codeberg4s.core.CodebergResponse.apply]]'s job, done once for every adapter, so it is not repeated
    * here.
    */
  private def grouped(headers: Seq[Header]): Map[String, List[String]] =
    headers
      .groupMap(_.name)(_.value)
      .map((name, values) => (name, values.toList))

  /** Obtaining a Future can itself fail at a foreign API boundary. Direct fatal throws remain fatal; already boxed
    * fatal failures are preserved by [[adapt]].
    */
  private def guarded[A](effect: => Future[A]): Future[A] =
    try effect
    catch
      case error: InterruptedException => Future.failed(error)
      case NonFatal(error)             => Future.failed(error)

  private def adapt[A](
      result: Try[Either[TransportFailure, A]],
      redaction: DiagnosticRedaction,
  ): Try[Either[TransportFailure, A]] =
    result match
      case failure @ Failure(error) =>
        FailureCauses.inspect(error) match
          case Some(graph) if !graph.all.exists(isFatal) && !suppressedInterrupt(graph) =>
            Success(Left(TransportFailure(classify(graph.primary, error, redaction))))
          case _ => failure // fatal/unsafe cleanup or an unseen tail is never a retryable library failure
      case success                  => success

  /** Cleanup may preserve an ordinary primary failure, but must not hide fatal/interrupt causes inside a nonfatal box.
    * An unseen over-budget tail remains a failed effect, matching the adapter's conservative cause policy.
    */
  private[transport] def mustPropagateCleanupFailure(error: Throwable): Boolean =
    FailureCauses.inspect(error).fold(true)(_.all.exists(cause => !NonFatal(cause)))

  private def isFatal(error: Throwable): Boolean =
    error match
      case _: InterruptedException => false // intentional transport classification, not VM failure
      case other                   => !NonFatal(other)

  /** Primary interrupts retain the existing transport diagnosis. An interruption suppressed by native cleanup must
    * remain a failed effect, not be hidden behind a retryable primary IOException.
    */
  private def suppressedInterrupt(graph: FailureCauses.Graph): Boolean =
    graph.all.exists:
      case interrupt: InterruptedException => !graph.primary.exists(_.eq(interrupt))
      case _                               => false

  /** Specific causes win over *all* generic I/O ancestors. Between specific categories the outermost wins, documenting
    * wrapper precedence explicitly. Fatal causes have already been checked across the entire bounded chain.
    */
  private def classify(
      causes: List[Throwable],
      original: Throwable,
      redaction: DiagnosticRedaction,
  ): TransportCause =
    causes.iterator.flatMap(error => specific(error, redaction)).nextOption() match
      case Some(diagnosis) => diagnosis
      case None            =>
        val ioFailure = causes.collectFirst:
          case error: IOException => TransportCause.ConnectionFailed(detail(error, redaction))
        ioFailure.getOrElse(TransportCause.Unknown(detail(causes.lastOption.getOrElse(original), redaction)))

  private def specific(error: Throwable, redaction: DiagnosticRedaction): Option[TransportCause] =
    error match
      case _: UnknownHostException             => Some(TransportCause.Dns(detail(error, redaction)))
      case _: SocketTimeoutException           => Some(TransportCause.Timeout(detail(error, redaction)))
      case _: HttpTimeoutException             => Some(TransportCause.Timeout(detail(error, redaction)))
      case _: javax.net.ssl.SSLException       => Some(TransportCause.Tls(detail(error, redaction)))
      case _: InterruptedException             => Some(TransportCause.Interrupted(detail(error, redaction)))
      case _: StreamMaxLengthExceededException => Some(TransportCause.ResponseTooLarge(detail(error, redaction)))
      case _: TimeoutException                 => Some(TransportCause.Timeout(detail(error, redaction)))
      case _                                   => None

  private def detail(error: Throwable, redaction: DiagnosticRedaction): String =
    val message = Option(error.getMessage).filter(_.trim.nonEmpty).getOrElse(error.getClass.getName)
    // Redact the complete message before clipping; a secret straddling the
    // excerpt boundary must not leave its unmatched prefix in diagnostics.
    val safe    = redaction.text(message).trim
    if safe.length <= MaxDetailLength then safe else s"${safe.take(MaxDetailLength)}..."

  /** Pinned sttp converts toMillis with truncation. Preserve the exact logical timer duration, rounding only the native
    * option up. FiniteDuration cannot represent the final partial millisecond at Long.MaxValue nanoseconds; that edge
    * saturates to its largest representable whole millisecond, while the required logical deadline remains exact and
    * armed.
    */
  private[transport] def nativeReadTimeout(duration: FiniteDuration): FiniteDuration =
    val nanos   = duration.toNanos
    val millis  = nanos / 1000000L
    val rounded = millis + (if nanos % 1000000L > 0L then 1L else 0L)
    FiniteDuration(math.min(rounded, Long.MaxValue / 1000000L), MILLISECONDS)
