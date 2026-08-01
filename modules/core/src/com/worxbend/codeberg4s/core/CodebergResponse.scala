package com.worxbend.codeberg4s.core

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import java.util.Locale

/** One HTTP response, described independently of any HTTP library.
  *
  * Every accessor on this type parses defensively. Forgejo omits `x-total-count` on several list endpoints, some
  * reverse proxies rewrite `Retry-After` into a date, and a self-hosted instance behind a gateway may send neither: a
  * missing or malformed header is always `None` and never an error, because a client that fails a repository listing
  * because a header was unparseable is worse than one that paginates without a total.
  *
  * @param status
  *   the HTTP status code, including `5xx` — a status is never a transport failure
  * @param headers
  *   response headers with '''already lowercased''' keys, as the transport adapter normalises them; a key maps to every
  *   value the server sent for it, in order
  * @param body
  *   the response body as text, empty for a `204`
  */
final case class CodebergResponse(status: Int, headers: Map[String, List[String]], body: String):

  /** The first value of `name`, matched case-insensitively.
    *
    * A header present but blank counts as absent, since that is what a gateway that strips a value leaves behind. The
    * returned value is trimmed.
    */
  def header(name: String): Option[String] =
    headers
      .get(name.toLowerCase(Locale.ROOT))
      .flatMap(_.headOption)
      .map(_.trim)
      .filter(_.nonEmpty)

  /** How many items the whole collection holds, from `x-total-count`.
    *
    * `None` means the instance did not say — never zero, and never an error. A value that is not a non-negative decimal
    * integer is treated as absent.
    */
  def totalCount: Option[Int] =
    header(CodebergResponse.TotalCountHeader).flatMap(CodebergResponse.nonNegativeInt)

  /** How long the server asked the client to wait, from `Retry-After`.
    *
    * Only the delta-seconds form is understood; the HTTP-date form is treated as absent, because honouring it needs a
    * trustworthy clock on both ends and Forgejo sends seconds. The retry engine clamps whatever comes back to the
    * policy's maximum delay.
    */
  def retryAfter: Option[FiniteDuration] =
    header(CodebergResponse.RetryAfterHeader).flatMap(CodebergResponse.nonNegativeInt).map(_.seconds)

  /** The instance's correlation id, from `x-request-id`, when it sent one. Copied into every failure's call context. */
  def requestId: Option[String] =
    header(CodebergResponse.RequestIdHeader)

object CodebergResponse:

  /** The header carrying the size of the whole collection. */
  val TotalCountHeader: String = "x-total-count"

  /** The header carrying the server-requested backoff. */
  val RetryAfterHeader: String = "retry-after"

  /** The header carrying the instance's correlation id. */
  val RequestIdHeader: String = "x-request-id"

  private def nonNegativeInt(value: String): Option[Int] =
    value.toIntOption.filter(_ >= 0)
