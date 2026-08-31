package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.{
  CodebergClient,
  CodebergConfig,
  CodebergError,
  CodebergException,
  Owner,
  RepoName,
  ValidationError
}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

/** The two error rails, side by side on the same failing call, and every case of the error ADT.
  *
  * ==Running it==
  *
  * {{{
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.HandlingErrors
  * }}}
  *
  * ==Environment==
  *
  * None. The program asks codeberg.org for a repository that does not exist, anonymously, which is a `404` any reader
  * can reproduce.
  *
  * ==The two rails==
  *
  * Every operation appears twice. `client.repos.get(owner, name)` returns `Future[Repository]` and fails that `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]]; `client.repos.attempt.get(owner, name)` returns
  * `Future[Either[CodebergError, Repository]]` and never fails. The second is the first with its failure channel
  * materialised, so the two cannot disagree about what an operation does — choosing a rail is a choice of style, never
  * of behaviour.
  *
  * ==There is no `NotFound` case==
  *
  * This is the single most common wrong assumption about the library, so the program prints the proof. The ADT has
  * exactly six cases:
  *
  *   - `Transport` — nothing reached the server;
  *   - `Api` — the server answered, and `status` is the HTTP status;
  *   - `DecodingFailed` — a 2xx body did not match the model;
  *   - `Validation` — an argument was rejected before a request was built;
  *   - `RetriesExhausted` — the retry engine gave up, wrapping the failure that ended it;
  *   - `WalkTruncated` — a walk over every page hit its page cap with pages still to come.
  *
  * A `404` is `Api(ctx, 404, body)`. A `429` is `Api(ctx, 429, body)`, or a `RetriesExhausted` wrapping one once the
  * policy has run out of attempts. Nothing else exists to match on.
  */
object HandlingErrors:

  private val AwaitLimit: FiniteDuration = 2.minutes

  /** A repository that will not exist. The owner is real so the failure is a plain `404` on the repository rather than
    * anything to do with the account.
    */
  private val missing: Either[ValidationError, (Owner, RepoName)] =
    for
      owner <- Owner.from("forgejo")
      name  <- RepoName.from("codeberg4s-no-such-repository")
    yield (owner, name)

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))

    try
      missing match
        case Left(problem) =>
          ExampleConsole.line(s"invalid ${problem.field}: ${problem.message}")

        case Right((owner, name)) =>
          ExampleConsole.heading("convenience rail — the Future fails with CodebergException")
          ExampleConsole.line(s"  ${Await.result(convenienceRail(client, owner, name), AwaitLimit)}")

          ExampleConsole.heading("typed rail — the Future succeeds with a Left")
          ExampleConsole.line(s"  ${Await.result(typedRail(client, owner, name), AwaitLimit)}")
    finally client.close()

  /** The rail that suits code already written in terms of failed `Future`s.
    *
    * `recover` sees a [[com.worxbend.codeberg4s.CodebergException]], and the error inside it is the same value the
    * typed rail would have handed back. Match on `error`; do not parse `getMessage`.
    */
  private def convenienceRail(client: CodebergClient, owner: Owner, name: RepoName)(using
      ExecutionContext): Future[String] =
    client.repos
      .get(owner, name)
      .map(describeSuccess)
      .recover:
        case CodebergException(error) => classify(error)

  /** The rail that keeps failures in the value channel.
    *
    * The returned `Future` never fails with a [[com.worxbend.codeberg4s.CodebergException]], so a `Left` is the only
    * way a remote failure arrives. A `Future` from this rail can still fail for reasons that are not this library's —
    * an execution context rejecting work, for instance — but never for a `404`.
    */
  private def typedRail(client: CodebergClient, owner: Owner, name: RepoName)(using ExecutionContext): Future[String] =
    client.repos.attempt
      .get(owner, name)
      .map:
        case Left(error)       => classify(error)
        case Right(repository) => describeSuccess(repository)

  private def describeSuccess(repository: Repository): String =
    s"found ${repository.fullName} with ${repository.starsCount} stars"

  /** Every case of the ADT, matched exhaustively.
    *
    * The first two clauses are both [[com.worxbend.codeberg4s.CodebergError.Api]]. That is the point: a `404` is
    * reached by matching the status inside `Api`, because there is no `NotFound` case to match instead. Deleting the
    * first clause changes nothing but the wording — the second already covers it.
    */
  private def classify(error: CodebergError): String =
    error match
      case CodebergError.Api(ctx, 404, _) =>
        s"404 from ${ctx.operation}: no such repository, or one this token cannot see — Forgejo does not distinguish"

      case CodebergError.Api(ctx, status, body) =>
        val message = body.message.getOrElse("no message from the server")
        s"$status from ${ctx.operation} after ${ctx.durationMs}ms: $message"

      case CodebergError.Transport(ctx, cause) =>
        // Almost always "nothing arrived". The one case where something did is
        // TransportCause.ResponseTooLarge — the body passed the configured bound
        // and reading it was abandoned — so the wording stays neutral and lets
        // `describe` say which it was.
        s"no usable response for ${ctx.operation}: ${cause.describe}"

      case CodebergError.DecodingFailed(ctx, snippet, path, cause) =>
        s"${ctx.operation} answered 2xx but ${path.render} did not decode ($cause); body began $snippet"

      case CodebergError.Validation(problem) =>
        s"rejected before any request was built — invalid ${problem.field}: ${problem.message}"

      case CodebergError.RetriesExhausted(ctx, attempts, last) =>
        // `last` is never discarded, so a 429 that outlived the policy is still
        // an Api(429) once this case is unwrapped.
        s"${ctx.operation} gave up after $attempts attempts; the last failure was: ${classify(last)}"

      case CodebergError.WalkTruncated(pagesVisited, resumeFrom) =>
        // The only case with no CallContext, because nothing went wrong on the
        // wire: every one of those pages arrived. What failed is the walk's
        // promise to cover the whole collection, so the partial answer is
        // refused rather than returned, and `resumeFrom` is where a caller who
        // wants the rest starts the next walk.
        s"the page walk covered $pagesVisited pages and the collection was still going; " +
          s"resume at page ${resumeFrom.page.value}"
