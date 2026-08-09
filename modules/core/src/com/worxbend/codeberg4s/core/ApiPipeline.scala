package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.core.Exec.flatMap
import com.worxbend.codeberg4s.core.Exec.map
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.syntax.discard

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** The single path every API call takes: send, retry, classify, decode, observe.
  *
  * Endpoints describe *what* to call by building a [[CodebergRequest]]; this class owns *how* a call is made. Keeping
  * it in one place is what makes the cross-cutting guarantees checkable rather than aspirational — every failure
  * carries a [[com.worxbend.codeberg4s.CallContext]] with a redacted URI, every attempt is observed, and no endpoint
  * gets to decide for itself what a `503` means.
  *
  * '''Order of events for one attempt.''' [[Telemetry.onRequest]], the send, then [[Telemetry.onResponse]] if a
  * response arrived, then [[Telemetry.onError]] if the attempt failed. The retry engine repeats that whole sequence, so
  * a retried call produces one triple per attempt, and [[Telemetry.onError]] is called once more with the failure the
  * caller finally receives — which is [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when the retry policy
  * ran out of attempts on a failure it was repeating, and the last failure unwrapped otherwise. A telemetry callback
  * that fails in `F`'s error channel is swallowed here: observation must not decide whether a request succeeded. A
  * callback that fails some other way — a raw throw, or an `F` whose failure channel is wider than
  * [[com.worxbend.codeberg4s.CodebergError]] — is out of this class's reach, because [[Exec.attempt]] deliberately
  * catches nothing else; whoever hands a caller's sink to this pipeline is responsible for wrapping it.
  *
  * '''Failure contract.''' `Left`/raised values are always a [[com.worxbend.codeberg4s.CodebergError]]:
  *   - no response at all becomes [[com.worxbend.codeberg4s.CodebergError.Transport]];
  *   - a non-2xx status becomes [[com.worxbend.codeberg4s.CodebergError.Api]] carrying the parsed error payload, or
  *     [[com.worxbend.codeberg4s.ApiErrorBody.Empty]] when the payload could not be read. An unreadable error body
  *     never masks the status;
  *   - a 2xx payload that does not decode becomes [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] with the
  *     failing JSON path and an excerpt of the body bounded at
  *     [[com.worxbend.codeberg4s.CodebergError.MaxSnippetLength]] — or, when the [[Decode]] instance declared itself
  *     [[Decode.sensitive]] because the endpoint answers with a credential, [[ApiPipeline.redactedSnippet]] in place of
  *     the excerpt;
  *   - a failure the retry engine gave up on becomes [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]],
  *     preserving the last underlying failure.
  *
  * Duration is measured with [[Timer.nowMillis]] on both sides of the send rather than with a system clock, so the
  * `durationMs` on every context is exact and reproducible under a fake timer.
  *
  * @param http
  *   the transport port; the only thing here that touches the network
  * @param config
  *   the deployment being talked to — the base URI the redacted URI is built from, and the retry policy
  * @param timer
  *   measures attempt duration and performs the retry backoff
  * @param telemetry
  *   observation hooks; failures from these are discarded
  * @param errorBody
  *   parses a non-2xx payload. Core cannot read JSON, so the `codec` module injects the real parser and a test injects
  *   a stub. It may fail however it likes — an exception from it yields [[com.worxbend.codeberg4s.ApiErrorBody.Empty]]
  * @tparam F
  *   the effect the client runs in
  */
final class ApiPipeline[F[_]](
    http: HttpPort[F],
    config: CodebergConfig,
    timer: Timer[F],
    telemetry: Telemetry[F],
    errorBody: String => ApiErrorBody,
)(using exec: Exec[F])(using JitterSource):

  private val engine: RetryEngine[F] = RetryEngine[F](config.retry, timer)

  /** Sends `request` under the retry policy and decodes a successful body into `A`.
    *
    * @param request
    *   the call to make
    * @param eligibility
    *   whether repeating this call is acceptable at all; a mutating endpoint passes [[RetryEligibility.IdempotentOnly]]
    *   or [[RetryEligibility.Never]]
    * @return
    *   the decoded value, or a [[com.worxbend.codeberg4s.CodebergError]] in `F`'s error channel
    */
  def call[A](request: CodebergRequest, eligibility: RetryEligibility)(using decode: Decode[A]): F[A] =
    perform(request, eligibility)((ctx, response) => decoded[A](ctx, response.body))

  /** As [[call]], but for an endpoint that answers `204` or whose body is deliberately ignored.
    *
    * The body is never looked at, so an instance that decorates a `204` with an unexpected payload cannot fail the
    * call. Non-2xx statuses are classified exactly as in [[call]].
    */
  def callUnit(request: CodebergRequest, eligibility: RetryEligibility): F[Unit] =
    perform(request, eligibility)((_, _) => Right(()))

  /** As [[call]], but assembles a [[com.worxbend.codeberg4s.paging.Page]] from the response's paging headers.
    *
    * Listing endpoints are `GET`s, so the call is retried under [[RetryEligibility.IdempotentOnly]]. Paging metadata
    * comes from [[Pages.from]], which reads `rel="next"` and never the number of items returned.
    *
    * @param request
    *   the listing call, with `page` and `limit` already in its query
    * @param params
    *   the window that was requested; it is kept on the returned page
    */
  def callPage[A](request: CodebergRequest, params: PageParams)(using decode: Decode[Vector[A]]): F[Page[A]] =
    perform(request, RetryEligibility.IdempotentOnly): (ctx, response) =>
      decoded[Vector[A]](ctx, response.body).map(items => Pages.from(response, params, items))

  /** Sends `request` and returns its body as bytes, for the endpoints that answer an archive rather than text.
    *
    * The transport that serves bytes is passed here rather than held on the pipeline, so that the many operations which
    * never need one are unaffected and every existing [[HttpPort]] fake keeps compiling.
    *
    * Retry, telemetry, status mapping and `CallContext` behave exactly as they do for a textual call. An error body is
    * still JSON text even on an endpoint whose success body is binary, so a non-2xx response is decoded with the
    * charset it declared and parsed the usual way; a successful body is never decoded, which is the whole point.
    *
    * Always [[RetryEligibility.IdempotentOnly]] — every endpoint that answers bytes in this API is a `GET`.
    */
  def callBinary(request: CodebergRequest, binary: BinaryHttpPort[F]): F[BinaryResponse] =
    val uri      = redactedUri(request)
    val attempts = engine.runWith(request.operation, request.method, RetryEligibility.IdempotentOnly)(_ =>
      binaryAttempt(request, uri, binary)
    )
    exec.attempt(attempts).flatMap:
      case Right(value) => exec.pure(value)
      case Left(error)  => reportFinal(request, error).flatMap(_ => exec.raise(error))

  private def binaryAttempt(
      request: CodebergRequest,
      uri: String,
      binary: BinaryHttpPort[F],
  ): F[AttemptOutcome[BinaryResponse]] =
    timer.nowMillis.flatMap: started =>
      observe(telemetry.onRequest(contextOf(request, uri, None, 0L))).flatMap: _ =>
        binary.sendBinary(request, uri).flatMap: sent =>
          timer.nowMillis.flatMap: finished =>
            settleBinary(request, uri, finished - started, sent)

  private def settleBinary(
      request: CodebergRequest,
      uri: String,
      elapsedMs: Long,
      sent: Either[TransportFailure, BinaryResponse],
  ): F[AttemptOutcome[BinaryResponse]] =
    sent match
      case Left(failure)   =>
        val ctx = contextOf(request, uri, None, elapsedMs)
        failedWith(ctx, CodebergError.Transport(ctx, failure.cause), None)
      case Right(response) =>
        val ctx = contextOf(request, uri, response.requestId, elapsedMs)
        observe(telemetry.onResponse(ctx, response.status)).flatMap: _ =>
          if StatusMapping.isSuccess(response.status) then exec.pure(AttemptOutcome.succeeded(response))
          else
            val body  = ResponseBody.of(response.bytes, ResponseBody.charsetOf(response.contentType))
            val error = StatusMapping.toError(ctx, response.status, parsedErrorBody(body))
            failedWith(ctx, error, response.retryAfter)

  private def perform[A](request: CodebergRequest, eligibility: RetryEligibility)(
      onSuccess: (CallContext, CodebergResponse) => Either[CodebergError, A]
  ): F[A] =
    val uri      = redactedUri(request)
    val attempts = engine.runWith(request.operation, request.method, eligibility)(_ =>
      attemptOnce(request, uri, onSuccess)
    )
    exec.attempt(attempts).flatMap:
      case Right(value) => exec.pure(value)
      case Left(error)  => reportFinal(request, error).flatMap(_ => exec.raise(error))

  /** Renders the URI every attempt of this call reports.
    *
    * A retried call re-sends an identical request, so the redacted URI it reports is identical too. Building it here,
    * once per call rather than once per attempt, keeps the string every attempt shares — and therefore every
    * `CallContext` and every telemetry event — exactly what it was, while a five-attempt call encodes its path and
    * query once instead of five times.
    */
  private def redactedUri(request: CodebergRequest): String =
    Redaction.uri(config.baseUri.value, request.path, request.query)

  private def attemptOnce[A](
      request: CodebergRequest,
      uri: String,
      onSuccess: (CallContext, CodebergResponse) => Either[CodebergError, A],
  ): F[AttemptOutcome[A]] =
    timer.nowMillis.flatMap: started =>
      observe(telemetry.onRequest(contextOf(request, uri, None, 0L))).flatMap: _ =>
        http.send(request, uri).flatMap: sent =>
          timer.nowMillis.flatMap: finished =>
            settle(request, uri, finished - started, sent, onSuccess)

  private def settle[A](
      request: CodebergRequest,
      uri: String,
      elapsedMs: Long,
      sent: Either[TransportFailure, CodebergResponse],
      onSuccess: (CallContext, CodebergResponse) => Either[CodebergError, A],
  ): F[AttemptOutcome[A]] =
    sent match
      case Left(failure)   =>
        val ctx = contextOf(request, uri, None, elapsedMs)
        failedWith(ctx, CodebergError.Transport(ctx, failure.cause), None)
      case Right(response) =>
        val ctx = contextOf(request, uri, response.requestId, elapsedMs)
        observe(telemetry.onResponse(ctx, response.status)).flatMap: _ =>
          if StatusMapping.isSuccess(response.status) then succeed(ctx, response, onSuccess)
          else
            val error = StatusMapping.toError(ctx, response.status, parsedErrorBody(response.body))
            failedWith(ctx, error, response.retryAfter)

  private def succeed[A](
      ctx: CallContext,
      response: CodebergResponse,
      onSuccess: (CallContext, CodebergResponse) => Either[CodebergError, A],
  ): F[AttemptOutcome[A]] =
    onSuccess(ctx, response) match
      case Right(value) => exec.pure(AttemptOutcome.succeeded(value))
      case Left(error)  => failedWith(ctx, error, None)

  private def failedWith[A](
      ctx: CallContext,
      error: CodebergError,
      retryAfter: Option[FiniteDuration],
  ): F[AttemptOutcome[A]] =
    observe(telemetry.onError(ctx, error)).map(_ => AttemptOutcome(Left(error), retryAfter))

  private def reportFinal(request: CodebergRequest, error: CodebergError): F[Unit] =
    observe(telemetry.onError(RetryEngine.contextOf(request.operation, request.method, error), error))

  /** Runs an observation hook, discarding whatever it reports. See the class-level note on telemetry failures. */
  private def observe(effect: F[Unit]): F[Unit] =
    exec.attempt(effect).map(_.discard)

  private def contextOf(
      request: CodebergRequest,
      uri: String,
      requestId: Option[String],
      elapsedMs: Long,
  ): CallContext =
    CallContext(request.operation, request.method, uri, requestId, elapsedMs)

  private def decoded[A](ctx: CallContext, body: ResponseBody)(using decode: Decode[A]): Either[CodebergError, A] =
    decode(body).left.map(failure =>
      CodebergError.DecodingFailed(ctx, ApiPipeline.snippetOf(body, decode.sensitive), failure.path, failure.message)
    )

  /** Reads a non-2xx payload, tolerating both an empty body and an injected parser that fails outright.
    *
    * This is one of the few places that genuinely wants text: the injected parser takes a `String`, an error payload is
    * a few hundred bytes, and it is only ever read on the failure path. Decoding it here rather than at the transport
    * is what keeps the successful path — every listing, every read — free of the copy.
    */
  private def parsedErrorBody(body: ResponseBody): ApiErrorBody =
    if body.isBlank then ApiErrorBody.Empty
    else Try(errorBody(body.text)).getOrElse(ApiErrorBody.Empty)

object ApiPipeline:

  /** What a body is reported as when its [[Decode]] declared itself [[Decode.sensitive]].
    *
    * A fixed string, so nothing about the payload survives into it, but not an empty one: a reader still has to be able
    * to tell "the instance answered with a body this library refuses to quote" from "the instance answered with
    * nothing". The size is what remains — enough to distinguish a truncated response from a complete one that did not
    * match the model, and not enough to reconstruct a byte of it.
    *
    * @param bytes
    *   how many bytes the withheld body held, [[ResponseBody.size]] of the response
    */
  def redactedSnippet(bytes: Int): String =
    s"${Redaction.Mask} ($bytes bytes withheld)"

  /** What [[com.worxbend.codeberg4s.CodebergError.DecodingFailed.snippet]] carries for `body`.
    *
    * Ordinarily an excerpt no longer than [[com.worxbend.codeberg4s.CodebergError.MaxSnippetLength]] '''characters'''.
    * Bounding happens here, once, rather than at each call site: a decoding failure on a 40 MB repository listing must
    * not put 40 MB into an error value that an application is about to log. [[ResponseBody.excerpt]] does the work,
    * because bounding a body that is now bytes at a number of characters is a job with a trap in it — see its own
    * documentation for why slicing the bytes and decoding the slice is not the same thing.
    *
    * When `sensitive` is set, the excerpt is replaced by [[redactedSnippet]] and no part of the body is quoted. That is
    * decided here rather than by the endpoint that made the call, so it holds for the failure a caller receives
    * '''and''' for the one [[Telemetry.onError]] observes — the hook fires inside the pipeline, so anything an endpoint
    * scrubbed afterwards would already have been handed to a telemetry sink that logs what it is given.
    *
    * @param sensitive
    *   [[Decode.sensitive]] of the instance that read this body
    */
  private[core] def snippetOf(body: ResponseBody, sensitive: Boolean): String =
    if sensitive then redactedSnippet(body.size)
    else body.excerpt(CodebergError.MaxSnippetLength)
