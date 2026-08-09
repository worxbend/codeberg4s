package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ContentType
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.BinaryHttpPort
import com.worxbend.codeberg4s.core.BinaryResponse
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergResponse
import com.worxbend.codeberg4s.core.HttpPort
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.ResponseBody
import com.worxbend.codeberg4s.core.TransportFailure

import sttp.capabilities.Effect
import sttp.capabilities.StreamMaxLengthExceededException
import sttp.client4.Backend
import sttp.client4.BackendOptions
import sttp.client4.GenericRequest
import sttp.client4.PartialRequest
import sttp.client4.Request
import sttp.client4.Response
import sttp.client4.asByteArrayAlways
import sttp.client4.basicRequest
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.client4.multipart
import sttp.client4.wrappers.DelegateBackend
import sttp.model.Header
import sttp.model.HeaderNames
import sttp.model.MediaType
import sttp.model.Method
import sttp.model.Uri

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.util.control.NonFatal

import java.net.Authenticator
import java.net.ConnectException
import java.net.PasswordAuthentication
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.util.Locale
import java.util.concurrent.Executor

/** The sttp implementation of [[com.worxbend.codeberg4s.core.HttpPort]] for `Future`.
  *
  * This is the only class in the library that speaks to an HTTP library, and the only place credentials are turned into
  * wire bytes: [[com.worxbend.codeberg4s.auth.ApiToken.reveal]] and [[com.worxbend.codeberg4s.auth.Password.reveal]]
  * are called here and nowhere else.
  *
  * '''Failure contract.''' Every HTTP status — including `5xx` — is a `Right`, because deciding what a status means
  * belongs to [[com.worxbend.codeberg4s.core.StatusMapping]]. A `Left` means no complete response arrived, classified
  * into a [[com.worxbend.codeberg4s.TransportCause]] by walking the exception's cause chain; sttp wraps the original
  * `java.net` exception in an `SttpClientException`, so the outermost type is never the interesting one. An
  * unclassified non-fatal exception becomes [[com.worxbend.codeberg4s.TransportCause.Unknown]] rather than being
  * dropped, and a fatal error stays fatal. Every cause but [[com.worxbend.codeberg4s.TransportCause.ResponseTooLarge]]
  * means nothing arrived at all; see "Response size" below for the one that does not.
  *
  * '''Security contract.''' Nothing here logs, and nothing here renders a credential: the `Authorization` header is
  * built and handed straight to sttp, `toString` is deliberately opaque, and a [[TransportFailure]] carries only the
  * message of the exception that caused it. Credentials never reach the request URI, so the URI in an sttp exception
  * message is safe. The configured credential is also the only one that can be sent: a per-request header map naming
  * `Authorization` or `Proxy-Authorization` has that entry dropped, and the configured credential is applied after
  * every remaining header, so no request can carry two credentials or a caller-chosen one.
  *
  * '''Resource ownership.''' `backend` belongs to whoever created it. This class never closes it, not even on failure.
  * A backend built by [[SttpHttpPort.defaultBackend]] owns a JDK `java.net.http.HttpClient` and releases it when it is
  * closed; see that method for what "released" means and for why sttp's own backend does not manage to do it.
  *
  * '''Timeouts.''' `config.readTimeout` is applied per request. `config.connectTimeout` is a property of the backend in
  * sttp, so it is honoured only by a backend built through [[SttpHttpPort.defaultBackend]]; a caller who supplies their
  * own backend configures the connect timeout on that backend.
  *
  * '''Response size.''' Every request carries a byte bound, because this library reads whole bodies into memory and
  * never streams. [[send]] applies [[com.worxbend.codeberg4s.CodebergConfig.maxResponseBodyBytes]] and [[sendBinary]]
  * applies the larger [[com.worxbend.codeberg4s.CodebergConfig.maxDownloadBodyBytes]]; passing either abandons the
  * response as [[com.worxbend.codeberg4s.TransportCause.ResponseTooLarge]]. Unlike the connect timeout this holds for a
  * caller-supplied backend too, since sttp models it per request.
  *
  * @param backend
  *   the sttp backend requests are sent on, owned and closed by the caller
  * @param config
  *   the instance to talk to, the credentials to use, the user agent to send, and the read timeout and response-body
  *   bounds to apply
  */
final class SttpHttpPort(
    backend: Backend[Future],
    config: CodebergConfig,
)(using ExecutionContext)
    extends HttpPort[Future]
      with BinaryHttpPort[Future]:

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
      .readTimeout(config.readTimeout)

  /** Sends `request`, never throwing and never logging.
    *
    * `redactedUri` is deliberately unused: it exists so an adapter that reports what it dialled reports the safe
    * rendering, and this adapter reports nothing at all.
    */
  override def send(request: CodebergRequest, redactedUri: String): Future[Either[TransportFailure, CodebergResponse]] =
    dispatch(request, config.maxResponseBodyBytes, SttpHttpPort.succeed)

  /** Sends `request` and reports what came back as a [[com.worxbend.codeberg4s.core.BinaryResponse]] instead.
    *
    * Identical to [[send]] apart from the response type it assembles and the body bound it applies, because both read
    * the body the same way now. Two methods remain because core still has two response types; see
    * [[com.worxbend.codeberg4s.core.BinaryResponse]] for why that is expected to change.
    *
    * The bound is [[com.worxbend.codeberg4s.CodebergConfig.maxDownloadBodyBytes]] rather than
    * [[com.worxbend.codeberg4s.CodebergConfig.maxResponseBodyBytes]]: this is the path the ZIP-fetching operations
    * under `client.repos.actions.downloads` take, and a CI artifact is legitimately far bigger than the largest JSON
    * document Forgejo will produce.
    */
  override def sendBinary(
      request: CodebergRequest,
      redactedUri: String,
  ): Future[Either[TransportFailure, BinaryResponse]] =
    dispatch(request, config.maxDownloadBodyBytes, SttpHttpPort.succeedBinary)

  /** Deliberately opaque: this object holds the configured credentials, so it renders nothing about its state. */
  override def toString: String = "SttpHttpPort"

  /** Builds one request, sends it, and turns whatever arrived into `A`, or into a classified transport failure.
    *
    * `onResponse` is the only thing that differed between the textual and the byte-carrying path, so the build, the
    * send, the recovery and the exception classification are written once instead of twice.
    *
    * Two things can go wrong before a socket is touched: the configured base URI may not parse, and [[build]] may
    * refuse the body. Both are already `Left` values, so they short-circuit here into an already-completed `Future` and
    * the backend never sees the request.
    */
  private def dispatch[A](
      request: CodebergRequest,
      maxBodyBytes: Long,
      onResponse: Response[Array[Byte]] => Either[TransportFailure, A],
  ): Future[Either[TransportFailure, A]] =
    root.flatMap(uri => build(request, uri, maxBodyBytes)) match
      case Left(failure) => Future.successful(Left(failure))
      case Right(built)  =>
        built
          .send(backend)
          .map(onResponse)
          .recover:
            case error: InterruptedException => SttpHttpPort.fail(error)
            case NonFatal(error)             => SttpHttpPort.fail(error)

  /** Builds the sttp request, reading '''every''' response body as bytes, or refuses to build it at all.
    *
    * `asByteArrayAlways` rather than `asStringAlways`, and that single word is the point of this path. With
    * `asStringAlways`, sttp decodes the socket bytes into a `String`, and the JSON parser then encodes that `String`
    * straight back into a `byte[]` in order to read it — two full copies of every payload before a single field is
    * looked at. The charset sttp would have applied is not lost: it is read off `Content-Type` into
    * [[com.worxbend.codeberg4s.core.ResponseBody]], which applies it if and when something actually asks for text.
    *
    * `maxResponseBodyLength` is what keeps "read the whole body into memory" from meaning "read as much as the peer
    * cares to send". sttp stops reading at `maxBodyBytes` and fails the request with a
    * `sttp.capabilities.StreamMaxLengthExceededException`, which [[SttpHttpPort.classify]] turns into
    * [[com.worxbend.codeberg4s.TransportCause.ResponseTooLarge]]. Without it the only bound on a response is
    * `config.readTimeout` multiplied by the peer's bandwidth, which is not a bound.
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
  ): Either[TransportFailure, Request[Array[Byte]]] =
    SttpHttpPort
      .withBody(request.body, template)
      .map: carrying =>
        withAuth(carrying.headers(SttpHttpPort.callerHeaders(request.headers)*))
          .maxResponseBodyLength(maxBodyBytes)
          .method(Method(request.method.wireName), SttpHttpPort.target(uri, request))
          .response(asByteArrayAlways)

  /** The one sanctioned call site of `reveal`.
    *
    * `Auth.Token` becomes `Authorization: token <value>`, which is the spec's `AuthorizationHeaderToken` scheme — a
    * `Bearer` prefix is rejected by Forgejo. The header is applied after the caller's own headers so configuration
    * always wins; see [[build]] for why "after" is what decides that with sttp's replace-by-default semantics.
    */
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

  /** A `Future` backend built on the JDK HTTP client, with the Codeberg default connect timeout.
    *
    * '''Ownership.''' The returned backend belongs to the caller, who must call `close()` on it when done. This library
    * never closes a backend it did not create, and [[SttpHttpPort]] never closes one at all.
    *
    * Callbacks run on `ExecutionContext.global`; use the two-argument overload to choose another one.
    */
  def defaultBackend(): Backend[Future] =
    defaultBackend(CodebergConfig.DefaultConnectTimeout, ExecutionContext.global)

  /** A `Future` backend built on the JDK HTTP client, with an explicit connect timeout and execution context.
    *
    * '''Ownership.''' As above — the caller closes it, and closing it really does release the connection pool.
    *
    * '''Why the client is built here rather than by sttp.''' `HttpClientFutureBackend(options)` decides whether it may
    * release the `java.net.http.HttpClient` it creates by testing whether the `ExecutionContext` it was handed is also
    * a `java.util.concurrent.Executor` — if it is, sttp assumes the executor is the caller's to shut down and sets its
    * internal `closeClient` flag to `false`, after which `close()` releases nothing. Every ordinary `ExecutionContext`
    * — `ExecutionContext.global`, one from `ExecutionContext.fromExecutor`, the one a test framework supplies — is an
    * `ExecutionContextExecutor`, so that flag is always `false` and the pool always survived `close()`. Building the
    * client here and handing it to `HttpClientFutureBackend.usingClient` moves the decision to this library, which
    * knows it created the client and may therefore end it.
    *
    * The client is configured exactly as sttp configures its own: the connect timeout below, no redirect following
    * (sttp's `FollowRedirectsBackend` wrapper does that itself, and a client that also followed them would apply the
    * policy twice), the system proxy that `BackendOptions.Default` reads out of the standard `http.proxyHost` family of
    * properties, and `executionContext` as the client's executor when it is one.
    *
    * @param connectTimeout
    *   how long to wait for a connection to be established; the JDK models this per client, not per request
    * @param executionContext
    *   where response callbacks run, and the client's executor when it happens to be an `Executor` as well
    */
  def defaultBackend(connectTimeout: FiniteDuration, executionContext: ExecutionContext): Backend[Future] =
    owning(defaultHttpClient(connectTimeout, executionContext), executionContext)

  /** The JDK HTTP client [[defaultBackend]] builds for itself, before it is wrapped in a backend.
    *
    * Internal: it exists apart from [[defaultBackend]] so a test can hold the client and ask it whether closing the
    * backend terminated it. Callers outside the library have no reason to want the two halves separately.
    */
  private[codeberg4s] def defaultHttpClient(
      connectTimeout: FiniteDuration,
      executionContext: ExecutionContext,
  ): HttpClient =
    val configured = HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(connectTimeout.toJava)

    val executing = executionContext match
      case executor: Executor => configured.executor(executor)
      case _                  => configured

    BackendOptions.Default.proxy.fold(executing)(proxy => proxied(executing, proxy)).build()

  /** An sttp backend on `client` that shuts `client` down when it is closed.
    *
    * Internal, and the other half of [[defaultHttpClient]]: together they are [[defaultBackend]].
    */
  private[codeberg4s] def owning(client: HttpClient, executionContext: ExecutionContext): Backend[Future] =
    OwnedClientBackend(HttpClientFutureBackend.usingClient(client)(using executionContext), client)

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

  /** A backend that releases the JDK HTTP client underneath it, which is the one thing sttp's own `close` will not do.
    *
    * `close()` calls `shutdown()` and not `close()`. The JDK's `HttpClient.close()` waits until every in-flight request
    * has finished, and [[com.worxbend.codeberg4s.CodebergClient.close]] is documented as returning promptly so that an
    * ordinary `finally` block stays cheap. `shutdown()` starts the same orderly shutdown — requests already submitted
    * run to completion, no new one is accepted — and returns without waiting for it.
    *
    * The client's executor is not touched, because it is `executionContext`, and that belongs to the caller. Only a
    * client the JDK gave its own default executor loses one, and that executor was never anybody else's.
    */
  private final class OwnedClientBackend(delegate: Backend[Future], client: HttpClient)
      extends DelegateBackend[Future, Any](delegate),
        Backend[Future]:

    override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
      delegate.send(request)

    override def close(): Future[Unit] =
      client.shutdown()
      delegate.close()

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

  private def succeedBinary(response: Response[Array[Byte]]): Either[TransportFailure, BinaryResponse] =
    Right(BinaryResponse(response.code.code, lowercased(response.headers), response.body))

  /** The charset the response declared is captured here, next to the bytes, and applied nowhere yet.
    *
    * sttp used to make this decision inside `asStringAlways`; it now belongs to
    * [[com.worxbend.codeberg4s.core.ResponseBody]], which is where a reader that wants text asks for it. Reading the
    * header at this point rather than later matters because a `ResponseBody` outlives the sttp `Response` it came from.
    */
  private def succeed(response: Response[Array[Byte]]): Either[TransportFailure, CodebergResponse] =
    val body = ResponseBody.of(response.body, ResponseBody.charsetOf(response.contentType))
    Right(CodebergResponse(response.code.code, lowercased(response.headers), body))

  /** Generic in the success type so the textual and binary paths share one classification. */
  private def fail[A](error: Throwable): Either[TransportFailure, A] =
    Left(TransportFailure(classify(error)))

  /** Response header names are lowercased because Codeberg sends them lowercase over HTTP/2 and mixed-case elsewhere,
    * and [[com.worxbend.codeberg4s.core.CodebergResponse]] promises its callers already-lowercased keys. A header the
    * instance repeated keeps every value, in the order it sent them.
    */
  private def lowercased(headers: Seq[Header]): Map[String, List[String]] =
    headers
      .groupMap(header => header.name.toLowerCase(Locale.ROOT))(_.value)
      .map((name, values) => (name, values.toList))

  /** Walks the cause chain outward-in and reports the first cause this library recognises.
    *
    * sttp wraps a `java.net.UnknownHostException` in its own `SttpClientException.ConnectException`, so matching only
    * the outermost type would classify almost everything as unknown.
    */
  @tailrec
  private def classify(error: Throwable): TransportCause =
    error match
      case _: UnknownHostException             => TransportCause.Dns(detail(error))
      case _: SocketTimeoutException           => TransportCause.Timeout(detail(error))
      case _: HttpTimeoutException             => TransportCause.Timeout(detail(error))
      case _: javax.net.ssl.SSLException       => TransportCause.Tls(detail(error))
      case _: ConnectException                 => TransportCause.ConnectionFailed(detail(error))
      case _: SocketException                  => TransportCause.ConnectionFailed(detail(error))
      case _: InterruptedException             => TransportCause.Interrupted(detail(error))
      // Thrown by sttp when the body passes the request's maxResponseBodyLength.
      // It arrives wrapped in an SttpClientException.ReadException, which is why
      // this is a cause-chain walk and not a match on the outermost type.
      case _: StreamMaxLengthExceededException => TransportCause.ResponseTooLarge(detail(error))
      case _                                   =>
        Option(error.getCause).filterNot(_.eq(error)) match
          case Some(cause) => classify(cause)
          case None        => TransportCause.Unknown(detail(error))

  private def detail(error: Throwable): String =
    val message = Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(error.getClass.getName)
    if message.length <= MaxDetailLength then message else s"${message.take(MaxDetailLength)}..."
