package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.paging.PageParams

import scala.concurrent.duration.FiniteDuration

/** Every failure this library reports, as one closed family.
  *
  * Recoverable failures are values: no operation throws for a `404`, a timeout, or a malformed payload. Each remote
  * case carries a [[CallContext]] so a caller can tell *which* call failed without correlating logs.
  *
  * Choosing a reaction:
  *   - [[CodebergError.Transport]] — nothing reached the server; safe to retry a safe method.
  *   - [[CodebergError.Api]] — the server answered; branch on `status`.
  *   - [[CodebergError.DecodingFailed]] — the server answered successfully but the payload did not match the model;
  *     retrying will not help, and `path` plus `snippet` is what a bug report needs.
  *   - [[CodebergError.Validation]] — the request was rejected before it was built; fix the argument.
  *   - [[CodebergError.RetriesExhausted]] — the retry engine gave up; `last` is the failure that ended it, never
  *     discarded.
  *   - [[CodebergError.WalkTruncated]] — a walk over every page hit its page cap with pages still to come; the answer
  *     it would otherwise have returned was incomplete, so it is not returned at all.
  */
enum CodebergError:

  /** The request never produced a response. */
  case Transport(ctx: CallContext, cause: TransportCause)

  /** The server answered with a non-2xx status. `body` is the parsed Forgejo payload, or [[ApiErrorBody.Empty]].
    *
    * `retryAfter` is the delay the server asked for in its `Retry-After` header, so a caller that catches a `429`
    * outside the retry engine — or reads the `last` of a [[CodebergError.RetriesExhausted]] — can schedule its own
    * backoff without re-reading headers it no longer has.
    *
    * It is `None` when the header was absent, blank, or in the HTTP-date form this library deliberately does not parse.
    * On a `429` that means "no usable hint was sent", '''not''' "retrying straight away is safe": with no hint, pick a
    * backoff of your own.
    */
  case Api(ctx: CallContext, status: Int, body: ApiErrorBody, retryAfter: Option[FiniteDuration])

  /** A 2xx payload could not be decoded. `snippet` is a bounded excerpt of the body, `path` says where it broke. */
  case DecodingFailed(ctx: CallContext, snippet: String, path: JsonPath, cause: String)

  /** A smart constructor rejected an argument before any request was built. */
  case Validation(error: ValidationError)

  /** The retry engine ran out of attempts. `last` preserves the failure of the final attempt. */
  case RetriesExhausted(ctx: CallContext, attempts: Int, last: CodebergError)

  /** A walk over every page of a collection stopped at its page cap while the server was still offering another page.
    *
    * This is not a remote failure — nothing went wrong on the wire — which is why it carries no [[CallContext]]. It
    * says that the result the walk was assembling covers only `pagesVisited` pages of a longer collection, so handing
    * that result back would be handing back a short answer indistinguishable from a complete one.
    *
    * `resumeFrom` is the window the walk was about to request, page size included. Passing it back as the starting
    * window continues exactly where this walk stopped, which is what makes the failure recoverable rather than merely
    * informative.
    *
    * @param pagesVisited
    *   how many pages were fetched and folded before the cap was reached
    * @param resumeFrom
    *   the page the walk would have requested next
    */
  case WalkTruncated(pagesVisited: Int, resumeFrom: PageParams)

object CodebergError:

  /** Upper bound, in characters, on any single free-form fragment [[describe]] embeds — body snippets, server messages
    * and decoder messages. Fragments longer than this are truncated and marked with an ellipsis.
    */
  val MaxSnippetLength: Int = 512

  private val Ellipsis: String = "..."

  extension (error: CodebergError)

    /** A bounded, secret-free, human-readable rendering.
      *
      * Safe to log and to put in an exception message: it is built only from the redacted [[CallContext]] and from
      * server-supplied text, and never from [[CodebergConfig]], so no token or password can reach it. Every free-form
      * fragment is truncated at [[MaxSnippetLength]], which bounds the output of a single level; nested
      * [[CodebergError.RetriesExhausted]] adds one bounded level per nesting step.
      */
    def describe: String =
      error match
        case Transport(ctx, cause)                     =>
          s"${renderContext(ctx)} transport failure: ${bound(cause.describe)}"
        case Api(ctx, status, body, retryAfter)        =>
          val message = body.message.fold("no message from the server")(bound)
          s"${renderContext(ctx)} responded $status: $message${renderDetails(body.errors)}${renderRetryAfter(retryAfter)}"
        case DecodingFailed(ctx, snippet, path, cause) =>
          s"${renderContext(ctx)} could not decode ${bound(path.render)}: ${bound(cause)}; body was ${bound(snippet)}"
        case Validation(problem)                       =>
          s"invalid ${problem.field}: ${bound(problem.message)}"
        case RetriesExhausted(ctx, attempts, last)     =>
          s"${renderContext(ctx)} gave up after $attempts attempts; last failure: ${bound(last.describe)}"
        case WalkTruncated(pagesVisited, resumeFrom)   =>
          // Every fragment is a number this library produced, so there is nothing
          // here for `bound` to protect against.
          s"page walk stopped after $pagesVisited pages with more pages still offered; " +
            s"resume at page ${resumeFrom.page.value} with limit ${resumeFrom.size.value}"

  /** The most detail fragments any one rendering embeds, so the whole string stays bounded rather than merely each
    * piece of it.
    *
    * A `422` can carry hundreds of field errors, and rendering all of them turns one failed call into a log line
    * megabytes long. The count that matters to a reader is small; the rest is noise, and the tail is elided with a
    * count so nothing looks silently complete.
    */
  private val MaxDetails: Int = 8

  private def renderRetryAfter(retryAfter: Option[FiniteDuration]): String =
    // A duration this library parsed itself, so it is already bounded.
    retryAfter.fold("")(delay => s"; retry after ${delay.toSeconds}s")

  private def renderContext(ctx: CallContext): String =
    // Every fragment here is bounded because two of the three are chosen by the
    // server: requestId is the x-request-id header verbatim, and uri carries
    // path segments and query values. An unbounded fragment lets a remote party
    // decide how long this library's log lines are.
    val requestId = ctx.requestId.fold("")(id => s" [request-id ${bound(id)}]")
    s"${ctx.operation} ${ctx.method.wireName} ${bound(ctx.uri)}$requestId after ${ctx.durationMs}ms"

  private def renderDetails(errors: List[String]): String =
    if errors.isEmpty then ""
    else
      val shown   = errors.take(MaxDetails).map(bound)
      val omitted = errors.length - shown.length
      val tail    = if omitted <= 0 then shown else shown :+ s"and $omitted more"
      tail.mkString(" (", "; ", ")")

  private def bound(value: String): String =
    if value.length <= MaxSnippetLength then value else s"${value.take(MaxSnippetLength)}$Ellipsis"
