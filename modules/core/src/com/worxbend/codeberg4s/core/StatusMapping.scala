package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.{ApiErrorBody, CallContext, CodebergError}

import scala.concurrent.duration.FiniteDuration

/** What an HTTP status means to this library.
  *
  * The mapping lives in one place so that no endpoint invents its own reading of a status code, and so that the set of
  * retryable statuses can be reviewed as a single list.
  */
object StatusMapping:

  /** The statuses worth attempting again: `429` plus the gateway-shaped `5xx` family.
    *
    * `500` is included because Forgejo answers a few transient database and Git-process failures with it. `501`, `505`
    * and the rest of `5xx` are not: they describe the instance's capabilities, and repeating the call only wastes the
    * caller's rate-limit budget.
    */
  val RetryableStatuses: Set[Int] = Set(429, 500, 502, 503, 504)

  /** Whether the status is a success, that is `2xx`.
    *
    * `3xx` is not a success here: redirects are followed by the transport adapter, so one reaching this point means the
    * instance sent something the client cannot use.
    */
  def isSuccess(status: Int): Boolean =
    status >= 200 && status < 300

  /** Whether a call that produced this status may be attempted again.
    *
    * Answering `true` says nothing about whether the '''call''' may be repeated: a `503` from a `POST` is retryable as
    * a status and still must not be retried automatically. That decision belongs to [[RetryEligibility]].
    */
  def isRetryable(status: Int): Boolean =
    RetryableStatuses.contains(status)

  /** Lifts a non-2xx response into the error channel.
    *
    * Callers parse the body first, because core cannot: an unparseable payload becomes
    * [[com.worxbend.codeberg4s.ApiErrorBody.Empty]] rather than a second failure. The status is preserved verbatim so
    * that a caller can branch on `404` against `409` without this library having to enumerate every endpoint's
    * vocabulary.
    *
    * `retryAfter` is whatever the response's `Retry-After` header yielded, already parsed by the caller; it travels
    * onto the error so that a caller handling a `429` itself can schedule a backoff from the value the server sent.
    */
  def toError(
      ctx: CallContext,
      status: Int,
      body: ApiErrorBody,
      retryAfter: Option[FiniteDuration],
  ): CodebergError =
    CodebergError.Api(ctx, status, body, retryAfter)
