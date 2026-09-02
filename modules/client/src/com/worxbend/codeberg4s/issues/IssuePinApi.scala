package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, remove}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The repository's pinned-issue shortlist: putting an issue on it, taking one off, and reordering it.
  *
  * Reached as `client.issues.pins`. It is a group of its own rather than more methods on [[IssueApi]] because that
  * class had grown past what a reader can hold in their head; the endpoints and the retry decisions are unchanged by
  * the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssuePinApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Pinning is a property of the repository, not of the issue==
  *
  * The shortlist is a curated ordering a maintainer keeps, which is why [[movePin]] exists at all: an issue's position
  * is a number in that list rather than anything the issue itself records. Reading the list back is
  * [[IssueApi.list]] with [[IssueQuery]], since Forgejo has no listing endpoint of its own for it.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the issue does not exist
  *     '''or''' is invisible to the credentials in use — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope or the account lacks the
  *     permission. `422` '''and''' `400` both mean the request was rejected as invalid.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path or a query parameter is rejected by its own smart
  * constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Every operation here names one issue and states its whole intended state, so each says on its own method how it is
  * retried.
  */

final class IssuePinApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssuePinApi.Attempt = IssuePinApi.Attempt(this)

  /** Pins an issue to the top of the repository's issue list — `POST /repos/{owner}/{repo}/issues/{index}/pin`.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. Repeating would be harmless — an
    * issue is either pinned or not — but the rule is the method; [[movePin]] is the call in this area that is retried,
    * and it says why.
    *
    * '''Answers `204`''', so there is nothing to return. Where the pinned issues can be '''read''' is
    * `GET /repos/{owner}/{repo}/issues/pinned`, which the repository group owns.
    *
    * '''Failures.''' The group contract above. A `403` is what exceeding the instance's limit on pinned issues looks
    * like, as well as an under-privileged token.
    */
  def pin(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssuePinApi.pinRequest(owner, name, number), RetryEligibility.Never)

  /** Unpins an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/pin`.
    *
    * '''Retried''', because the request names exactly one issue and asks for an absolute end state — that issue is not
    * pinned. Doing it twice leaves the repository exactly where doing it once would, nothing is created, and unlike
    * most deletes in this library a lost success does not turn into a `404`: the issue is still there, it simply has no
    * pin left to remove.
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def unpin(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssuePinApi.unpinRequest(owner, name, number), RetryEligibility.AlwaysRetry)

  /** Moves a pinned issue to a given slot — `PATCH /repos/{owner}/{repo}/issues/{index}/pin/{position}`.
    *
    * '''The one `PATCH` in this class that is retried''', and the exception is deliberate. Every other `PATCH` here
    * carries a body that is applied to whatever the resource has become, so a repeat can overwrite somebody else's
    * change. This one carries '''no body at all''': the whole request is a URL naming one issue and one absolute
    * position, so the state after N attempts is the state after one, and nothing is created. That is the bar the class
    * note states, and this call meets it where [[edit]] does not.
    *
    * '''One-based''', because Forgejo's own `pin_order` is `0` for an unpinned issue; see [[PinPosition]].
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above. A `404` covers an issue that is not pinned at all as well as one that
    * does not exist.
    */
  def movePin(owner: Owner, name: RepoName, number: IssueNumber, position: PinPosition): Future[Unit] =
    pipeline.callUnit(IssuePinApi.movePinRequest(owner, name, number, position), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object IssuePinApi:

  /** The stable operation id of [[IssuePinApi.pin]]. */
  val PinOperation: String = "issues.pin"

  /** The stable operation id of [[IssuePinApi.unpin]]. */
  val UnpinOperation: String = "issues.unpin"

  /** The stable operation id of [[IssuePinApi.movePin]]. */
  val MovePinOperation: String = "issues.pin.move"

  /** The typed rail of [[IssuePinApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.issues.pins.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from
    * both.
    */
  final class Attempt private[codeberg4s] (rail: IssuePinApi)(using exec: Exec[Future]):

    /** [[IssuePinApi.pin]] with its failure as a value. */
    def pin(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.pin(owner, name, number))

    /** [[IssuePinApi.unpin]] with its failure as a value. */
    def unpin(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unpin(owner, name, number))

    /** [[IssuePinApi.movePin]] with its failure as a value. */
    def movePin(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        position: PinPosition,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.movePin(owner, name, number, position))

  private def pinRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    bodiless(PinOperation, HttpMethod.Post, pinPath(owner, name, number))

  private def unpinRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    remove(UnpinOperation, pinPath(owner, name, number))

  private def movePinRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      position: PinPosition,
  ): CodebergRequest =
    bodiless(MovePinOperation, HttpMethod.Patch, pinPath(owner, name, number) :+ position.value.toString)

  private def pinPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "pin"
