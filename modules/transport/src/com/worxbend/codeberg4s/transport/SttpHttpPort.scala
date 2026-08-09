package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.CodebergConfig
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

import sttp.client4.Backend
import sttp.client4.BackendOptions
import sttp.client4.PartialRequest
import sttp.client4.Request
import sttp.client4.Response
import sttp.client4.asByteArrayAlways
import sttp.client4.basicRequest
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.client4.multipart
import sttp.model.Header
import sttp.model.HeaderNames
import sttp.model.MediaType
import sttp.model.Method
import sttp.model.Uri

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.util.Locale

/** The sttp implementation of [[com.worxbend.codeberg4s.core.HttpPort]] for `Future`.
  *
  * This is the only class in the library that speaks to an HTTP library, and the only place credentials are turned into
  * wire bytes: [[com.worxbend.codeberg4s.auth.ApiToken.reveal]] and [[com.worxbend.codeberg4s.auth.Password.reveal]]
  * are called here and nowhere else.
  *
  * '''Failure contract.''' Every HTTP status — including `5xx` — is a `Right`, because deciding what a status means
  * belongs to [[com.worxbend.codeberg4s.core.StatusMapping]]. A `Left` means no response arrived at all, classified
  * into a [[com.worxbend.codeberg4s.TransportCause]] by walking the exception's cause chain; sttp wraps the original
  * `java.net` exception in an `SttpClientException`, so the outermost type is never the interesting one. An
  * unclassified non-fatal exception becomes [[com.worxbend.codeberg4s.TransportCause.Unknown]] rather than being
  * dropped, and a fatal error stays fatal.
  *
  * '''Security contract.''' Nothing here logs, and nothing here renders a credential: the `Authorization` header is
  * built and handed straight to sttp, `toString` is deliberately opaque, and a [[TransportFailure]] carries only the
  * message of the exception that caused it. Credentials never reach the request URI, so the URI in an sttp exception
  * message is safe.
  *
  * '''Resource ownership.''' `backend` belongs to whoever created it. This class never closes it, not even on failure —
  * see [[SttpHttpPort.defaultBackend]].
  *
  * '''Timeouts.''' `config.readTimeout` is applied per request. `config.connectTimeout` is a property of the backend in
  * sttp, so it is honoured only by a backend built through [[SttpHttpPort.defaultBackend]]; a caller who supplies their
  * own backend configures the connect timeout on that backend.
  *
  * @param backend
  *   the sttp backend requests are sent on, owned and closed by the caller
  * @param config
  *   the instance to talk to, the credentials to use, the user agent to send and the read timeout to apply
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

  /** Sends `request`, never throwing and never logging.
    *
    * `redactedUri` is deliberately unused: it exists so an adapter that reports what it dialled reports the safe
    * rendering, and this adapter reports nothing at all.
    */
  override def send(request: CodebergRequest, redactedUri: String): Future[Either[TransportFailure, CodebergResponse]] =
    root match
      case Left(failure) => Future.successful(Left(failure))
      case Right(uri)    => dispatch(build(request, uri), SttpHttpPort.succeed)

  /** Sends `request` and reports what came back as a [[com.worxbend.codeberg4s.core.BinaryResponse]] instead.
    *
    * Identical to [[send]] apart from the response type it assembles, because both read the body the same way now. Two
    * methods remain because core still has two response types; see [[com.worxbend.codeberg4s.core.BinaryResponse]] for
    * why that is expected to change.
    */
  override def sendBinary(
      request: CodebergRequest,
      redactedUri: String,
  ): Future[Either[TransportFailure, BinaryResponse]] =
    root match
      case Left(failure) => Future.successful(Left(failure))
      case Right(uri)    => dispatch(build(request, uri), SttpHttpPort.succeedBinary)

  /** Deliberately opaque: this object holds the configured credentials, so it renders nothing about its state. */
  override def toString: String = "SttpHttpPort"

  /** Sends one request and turns whatever arrived into `A`, or into a classified transport failure.
    *
    * `onResponse` is the only thing that differed between the textual and the byte-carrying path, so the send, the
    * recovery and the exception classification are now written once instead of twice.
    */
  private def dispatch[A](
      request: Request[Array[Byte]],
      onResponse: Response[Array[Byte]] => Either[TransportFailure, A],
  ): Future[Either[TransportFailure, A]] =
    request
      .send(backend)
      .map(onResponse)
      .recover:
        case error: InterruptedException => SttpHttpPort.fail(error)
        case NonFatal(error)             => SttpHttpPort.fail(error)

  /** Builds the sttp request, reading '''every''' response body as bytes.
    *
    * `asByteArrayAlways` rather than `asStringAlways`, and that single word is the point of this path. With
    * `asStringAlways`, sttp decodes the socket bytes into a `String`, and the JSON parser then encodes that `String`
    * straight back into a `byte[]` in order to read it — two full copies of every payload before a single field is
    * looked at. The charset sttp would have applied is not lost: it is read off `Content-Type` into
    * [[com.worxbend.codeberg4s.core.ResponseBody]], which applies it if and when something actually asks for text.
    */
  private def build(request: CodebergRequest, uri: Uri): Request[Array[Byte]] =
    withAuth(SttpHttpPort.withBody(request.body, basicRequest))
      .headers(request.headers.map((name, value) => Header(name, value))*)
      .header(HeaderNames.UserAgent, config.userAgent.value)
      .readTimeout(config.readTimeout)
      .method(Method(request.method.wireName), SttpHttpPort.target(uri, request))
      .response(asByteArrayAlways)

  /** The one sanctioned call site of `reveal`.
    *
    * `Auth.Token` becomes `Authorization: token <value>`, which is the spec's `AuthorizationHeaderToken` scheme — a
    * `Bearer` prefix is rejected by Forgejo. The header is applied after the caller's own headers so configuration
    * always wins.
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
    * '''Ownership.''' As above — the caller closes it.
    *
    * @param connectTimeout
    *   how long to wait for a connection to be established; sttp configures this per backend, not per request
    * @param executionContext
    *   where response callbacks run
    */
  def defaultBackend(connectTimeout: FiniteDuration, executionContext: ExecutionContext): Backend[Future] =
    HttpClientFutureBackend(BackendOptions.Default.connectionTimeout(connectTimeout))(using executionContext)

  private def target(uri: Uri, request: CodebergRequest): Uri =
    uri.addPath(request.path).addParams(request.query*)

  private def withBody(
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
      case _: UnknownHostException       => TransportCause.Dns(detail(error))
      case _: SocketTimeoutException     => TransportCause.Timeout(detail(error))
      case _: HttpTimeoutException       => TransportCause.Timeout(detail(error))
      case _: javax.net.ssl.SSLException => TransportCause.Tls(detail(error))
      case _: ConnectException           => TransportCause.ConnectionFailed(detail(error))
      case _: SocketException            => TransportCause.ConnectionFailed(detail(error))
      case _: InterruptedException       => TransportCause.Interrupted(detail(error))
      case _                             =>
        Option(error.getCause).filterNot(_.eq(error)) match
          case Some(cause) => classify(cause)
          case None        => TransportCause.Unknown(detail(error))

  private def detail(error: Throwable): String =
    val message = Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(error.getClass.getName)
    if message.length <= MaxDetailLength then message else s"${message.take(MaxDetailLength)}..."
