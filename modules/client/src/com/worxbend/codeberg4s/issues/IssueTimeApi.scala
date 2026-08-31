package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.issues.wire.{AddTimeOptionDto, IssueQueries}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** Timetracking on an issue: the stopwatch that measures work as it happens, and the log of what was worked.
  *
  * Reached as `client.issues.times`. Seven operations, in two halves that are easy to confuse and are not the same
  * thing:
  *
  *   - a '''stopwatch''' is live. [[startStopwatch]] begins measuring, [[stopStopwatch]] ends it '''and files the
  *     elapsed time as a tracked-time entry''', and [[deleteStopwatch]] abandons it without filing anything. An account
  *     has at most one running stopwatch, which is why starting a second one is a `409`.
  *   - a '''tracked time''' is a filed record. [[list]] reads them, [[add]] files one directly without a stopwatch,
  *     [[delete]] removes one, and [[reset]] removes all of them on the issue.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueTimeApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Every endpoint here requires a token and
  * the golden harvest was anonymous, so no fixture covers `TrackedTime` or `AddTimeOption`.
  *
  * ==Seconds on the wire, durations in the domain==
  *
  * `TrackedTime.time` and `AddTimeOption.time` are both `int64` seconds. The conversion to
  * `scala.concurrent.duration.FiniteDuration` happens once at the wire boundary, so no call site here carries a unit;
  * see [[TrackedTime.spent]] and [[AddTrackedTime]], which refuses a sub-second duration rather than truncating it.
  *
  * ==Timetracking may be switched off==
  *
  * A repository with the feature disabled answers `400` or `404` rather than an empty list, and a token without write
  * access to the repository cannot toggle a stopwatch at all — the spec spells that `403` out in words on all three
  * stopwatch operations.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the issue does not exist
  *     '''or''' is private to credentials the client does not have — Forgejo does not distinguish the two, on purpose —
  *     `401` when a token was required and none was sent, and `403` when the token lacks the scope or may not toggle a
  *     stopwatch. `422` '''and''' `400` both mean the request was rejected as invalid, per `docs/HAZARDS.md` §4; `400`
  *     is also what timetracking being disabled looks like. `409` is specific to the stopwatch operations and means the
  *     stopwatch was already in the state being asked for.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class IssueTimeApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueTimeApi.Attempt = IssueTimeApi.Attempt(this)

  // --- the stopwatch --------------------------------------------------------

  /** Starts the authenticated account's stopwatch on an issue —
    * `POST /repos/{owner}/{repo}/issues/{index}/stopwatch/start`.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. A repeat is not harmless either:
    * the spec declares `409` for "cannot start a stopwatch again if it already exists", so a retry after a lost success
    * turns a success into a reported conflict. A caller who is unsure whether the stopwatch started finds out by
    * starting it again deliberately and reading the `409`.
    *
    * '''Answers `201` with no body''', so there is nothing to return. Where the running stopwatch can be '''read''' is
    * `GET /user/stopwatches`, which belongs to the user group, not here — this endpoint answers nothing.
    *
    * '''Failures.''' The group contract above.
    */
  def startStopwatch(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueTimeApi.startStopwatchRequest(owner, name, number), RetryEligibility.Never)

  /** Stops the stopwatch and files the elapsed time — `POST /repos/{owner}/{repo}/issues/{index}/stopwatch/stop`.
    *
    * '''This creates a tracked-time entry.''' That is what makes it different from [[deleteStopwatch]], and it is why a
    * repeat is not merely redundant.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one; the spec declares `409` for
    * "cannot stop a non existent stopwatch", so a retry after a lost success reports a conflict. [[list]] is how a
    * caller finds out whether the entry was filed.
    *
    * '''Answers `201` with no body''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def stopStopwatch(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueTimeApi.stopStopwatchRequest(owner, name, number), RetryEligibility.Never)

  /** Abandons the stopwatch without filing anything — `DELETE /repos/{owner}/{repo}/issues/{index}/stopwatch/delete`.
    *
    * '''No tracked time is created''', unlike [[stopStopwatch]]. This is the "I left that running by mistake" call.
    *
    * '''Retried''', because the request names exactly one thing to cancel — this issue, this account's stopwatch, of
    * which there is at most one — so the state after N attempts is the state after one and nothing is created. The cost
    * differs from most deletes in this library: a lost success followed by a retry answers `409`, "cannot cancel a non
    * existent stopwatch", rather than `404`.
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteStopwatch(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueTimeApi.deleteStopwatchRequest(owner, name, number), RetryEligibility.AlwaysRetry)

  // --- tracked time ---------------------------------------------------------

  /** Lists an issue's tracked time — `GET /repos/{owner}/{repo}/issues/{index}/times`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Totals are the caller's job.''' The endpoint reports entries, not a sum; add up [[TrackedTime.spent]], which is
    * a `FiniteDuration` precisely so that adding is safe.
    *
    * '''Failures.''' The group contract above. A `422` here most often means a malformed `since` or `before`.
    *
    * @param query
    *   which entries to include; [[TrackedTimeQuery.Empty]] asks for all of them
    */
  def list(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      query: TrackedTimeQuery,
      params: PageParams,
  ): Future[Page[TrackedTime]] =
    pipeline.callPage(IssueTimeApi.listRequest(owner, name, number, query, params), params)(using
      IssueDecoders.trackedTimes)

  /** Files worked time against an issue — `POST /repos/{owner}/{repo}/issues/{index}/times`.
    *
    * '''Never retried, and here that matters more than usual.''' A repeat files a '''second''' entry, so the issue ends
    * up reporting twice the time that was actually worked — a silent, plausible-looking corruption of exactly the data
    * this endpoint exists to record. Forgejo has no idempotency key that would let the instance recognise the repeat. A
    * transport failure therefore leaves the caller unsure whether the entry exists, which [[list]] resolves.
    *
    * '''Answers `200`''' with the created entry, including the [[TrackedTimeId]] [[delete]] needs.
    *
    * '''Failures.''' The group contract above. A `400` is what timetracking being disabled on the repository looks
    * like.
    */
  def add(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: AddTrackedTime,
  ): Future[TrackedTime] =
    pipeline.call(IssueTimeApi.addRequest(owner, name, number, command), RetryEligibility.Never)(using
      IssueDecoders.trackedTime)

  /** Deletes one tracked-time entry — `DELETE /repos/{owner}/{repo}/issues/{index}/times/{id}`.
    *
    * '''Retried''', because the request names exactly one object by an identifier the instance never reuses: a
    * tracked-time id is a database row id, so the state after N attempts is the state after one and nothing is created.
    * The cost is one a caller has to know — if the first attempt succeeded and its response was lost, the retry
    * addresses something that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone",
    * not necessarily "it was never there".
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def delete(owner: Owner, name: RepoName, number: IssueNumber, id: TrackedTimeId): Future[Unit] =
    pipeline.callUnit(IssueTimeApi.deleteRequest(owner, name, number, id), RetryEligibility.AlwaysRetry)

  /** Deletes '''every''' tracked-time entry on an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/times`.
    *
    * '''Not the same as [[delete]]''', and there is no undo: this discards the whole log, not one row.
    *
    * '''Retried''', because the request states an absolute end state — no entries on this issue — so repeating it
    * changes nothing and creates nothing. Unlike [[delete]], a lost success does not turn into a `404`: the issue is
    * still there, it simply has no time left to reset.
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above. A `400` is what timetracking being disabled looks like.
    */
  def reset(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueTimeApi.resetRequest(owner, name, number), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueTimeApi:

  /** The stable operation id of [[IssueTimeApi.startStopwatch]]. Safe to alert on. */
  val StartStopwatchOperation: String = "issues.stopwatch.start"

  /** The stable operation id of [[IssueTimeApi.stopStopwatch]]. */
  val StopStopwatchOperation: String = "issues.stopwatch.stop"

  /** The stable operation id of [[IssueTimeApi.deleteStopwatch]]. */
  val DeleteStopwatchOperation: String = "issues.stopwatch.delete"

  /** The stable operation id of [[IssueTimeApi.list]]. */
  val ListOperation: String = "issues.times.list"

  /** The stable operation id of [[IssueTimeApi.add]]. */
  val AddOperation: String = "issues.times.add"

  /** The stable operation id of [[IssueTimeApi.delete]]. */
  val DeleteOperation: String = "issues.times.delete"

  /** The stable operation id of [[IssueTimeApi.reset]]. */
  val ResetOperation: String = "issues.times.reset"

  /** The typed rail of [[IssueTimeApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.issues.times.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueTimeApi)(using exec: Exec[Future]):

    /** [[IssueTimeApi.startStopwatch]] with its failure as a value. */
    def startStopwatch(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.startStopwatch(owner, name, number))

    /** [[IssueTimeApi.stopStopwatch]] with its failure as a value. */
    def stopStopwatch(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.stopStopwatch(owner, name, number))

    /** [[IssueTimeApi.deleteStopwatch]] with its failure as a value. */
    def deleteStopwatch(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteStopwatch(owner, name, number))

    /** [[IssueTimeApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        query: TrackedTimeQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[TrackedTime]]] =
      exec.attempt(rail.list(owner, name, number, query, params))

    /** [[IssueTimeApi.add]] with its failure as a value. */
    def add(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: AddTrackedTime,
    ): Future[Either[CodebergError, TrackedTime]] =
      exec.attempt(rail.add(owner, name, number, command))

    /** [[IssueTimeApi.delete]] with its failure as a value. */
    def delete(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        id: TrackedTimeId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, number, id))

    /** [[IssueTimeApi.reset]] with its failure as a value. */
    def reset(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.reset(owner, name, number))

  private def startStopwatchRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    stopwatch(StartStopwatchOperation, HttpMethod.Post, owner, name, number, "start")

  private def stopStopwatchRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    stopwatch(StopStopwatchOperation, HttpMethod.Post, owner, name, number, "stop")

  private def deleteStopwatchRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    stopwatch(DeleteStopwatchOperation, HttpMethod.Delete, owner, name, number, "delete")

  /** The three stopwatch routes differ only in a verb segment and a method, and none of them carries a body — which is
    * why `delete` is a path segment here rather than the HTTP method alone.
    */
  private def stopwatch(
      operation: String,
      method: HttpMethod,
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      action: String,
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = IssueRequests.issuePath(owner, name, number) ++ List("stopwatch", action),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def listRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      query: TrackedTimeQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListOperation,
      timesPath(owner, name, number),
      IssueQueries.trackedTimes(query) ++ IssueQueries.paging(params),
    )

  private def addRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: AddTrackedTime,
  ): CodebergRequest =
    write(
      AddOperation,
      HttpMethod.Post,
      timesPath(owner, name, number),
      AddTimeOptionDto.render(command),
    )

  private def deleteRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: TrackedTimeId,
  ): CodebergRequest =
    remove(DeleteOperation, timesPath(owner, name, number) :+ id.value.toString)

  private def resetRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    remove(ResetOperation, timesPath(owner, name, number))

  private def timesPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "times"
