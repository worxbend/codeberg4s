package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.paging.PageNumber

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
  *   the response body as the bytes that arrived, with the charset the response declared for them; empty for a `204`.
  *   See [[ResponseBody]] for why this is not a `String`
  */
final case class CodebergResponse(status: Int, headers: Map[String, List[String]], body: ResponseBody):

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

  /** Every RFC 5988 link the response carried, keyed by lowercased relation type.
    *
    * All values of the `Link` header are considered, not just the first: a proxy is allowed to split one header into
    * several, and the RFC says the result is the same as if they had been joined with commas. An unreadable header
    * yields an empty map — see [[LinkHeader]] for why that is never an error.
    *
    * The header is parsed the first time this is read and the result is kept, because [[nextPage]], [[prevPage]] and
    * [[lastPage]] all go through it and page one alone would otherwise parse the same string three times.
    */
  lazy val links: Map[String, String] =
    LinkHeader.parse(headers.getOrElse(LinkHeader.Name, Nil).mkString(","))

  /** The page number of `rel="next"`, and nothing else.
    *
    * This is the only sound end-of-collection test against Forgejo: the instance clamps `limit` to its own maximum
    * while still echoing the requested limit in the `Link` header, so a short page does not mean the last page, and a
    * page past the end comes back as `200` with `[]` rather than `404`. `None` means the collection ends here.
    */
  def nextPage: Option[PageNumber] =
    pageOfRel(LinkHeader.Next)

  /** The page number of `rel="prev"`, accepting `rel="previous"` as a synonym. `None` when the response offered none. */
  def prevPage: Option[PageNumber] =
    pageOfRel(LinkHeader.Prev).orElse(pageOfRel(LinkHeader.Previous))

  /** The page number of `rel="last"`, when the instance advertised one.
    *
    * Useful for reporting progress, never for deciding when to stop: an instance behind a proxy that drops the header
    * still paginates correctly, and only [[nextPage]] decides that.
    */
  def lastPage: Option[PageNumber] =
    pageOfRel(LinkHeader.Last)

  private def pageOfRel(rel: String): Option[PageNumber] =
    links.get(rel).flatMap(CodebergResponse.pageIn)

object CodebergResponse:

  /** The header carrying the size of the whole collection. */
  val TotalCountHeader: String = "x-total-count"

  /** The header carrying the server-requested backoff. */
  val RetryAfterHeader: String = "retry-after"

  /** The header carrying the instance's correlation id. */
  val RequestIdHeader: String = "x-request-id"

  /** The query parameter Forgejo takes the one-based page index from. */
  val PageParameter: String = "page"

  private def nonNegativeInt(value: String): Option[Int] =
    value.toIntOption.filter(_ >= 0)

  /** The `page` parameter of a link target as a validated page number, absent when the target carries none or carries
    * something that is not a page index.
    */
  private def pageIn(target: String): Option[PageNumber] =
    LinkHeader
      .queryParameter(target, PageParameter)
      .flatMap(_.toIntOption)
      .flatMap(number => PageNumber.from(number).toOption)
