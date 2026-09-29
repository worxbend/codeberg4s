package com.worxbend.codeberg4s.notifications

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.notifications.wire.NotificationQueries
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

import java.time.Instant

/** The notification inbox: listing threads, reading one, and marking threads read.
  *
  * Reached as `client.notifications`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[NotificationApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This group is unverified, and that is not a formality==
  *
  * Every other endpoint group in this library was built against captured response bodies. This one could not be:
  * `GET /notifications` answers `401 token is required` without credentials, the golden harvest was anonymous, and
  * `golden/MANIFEST.md` accordingly records one '''synthetic''' fixture — `golden/notification/list-synthetic.json`,
  * hand-authored from `definitions.NotificationThread` and `definitions.NotificationSubject` in `spec/swagger.v1.json`.
  * The model, the field names, the optionality and the status codes below are therefore the spec's word, and
  * `docs/HAZARDS.md` §1 measured that the spec's word about response shapes is worth very little. Read
  * [[NotificationThread]] before depending on the shape of what comes back, and treat any live capture that contradicts
  * this group as correct.
  *
  * ==Authentication==
  *
  * Every operation here needs a token. There is nothing anonymous to list: the inbox is a property of the authenticated
  * user, which is also why the repository-scoped listing is "my notifications about this repository" and not "this
  * repository's notifications".
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no token was sent or the token expired —
  *     the common failure in this group, unlike anywhere else in the library — `403` when the token lacks the
  *     `notification` scope, and `404` when the thread or repository does not exist '''or''' is invisible to the
  *     credentials in use. `422` '''and''' `400` both mean the request was rejected as invalid: `docs/HAZARDS.md` §4
  *     captured Forgejo using `400` for a malformed identifier and `422` for a malformed timestamp, so a caller
  *     checking only for `422` will miss half of them, and `since`/`before` on the listings are exactly the sort of
  *     parameter that produces one.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field. Given the provenance above, this is the failure to watch here: it is the shape of
  *     "the spec was wrong", and the path in it is the bug report.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[com.worxbend.codeberg4s.Owner]], [[NotificationThreadId]], [[NotificationStatus]] — so
  * a value that would forge a path or a query parameter is rejected by its own smart constructor before a client is
  * ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The three mutating operations use
  * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], which is the only place in this library that does so,
  * and it is a deliberate claim about these endpoints rather than a convenience: marking an already-read thread read
  * again changes nothing observable, so repeating the call after a `503` cannot produce a second anything. Contrast
  * [[com.worxbend.codeberg4s.issues.IssueApi.create]], where a repeat files a second issue, and
  * [[com.worxbend.codeberg4s.issues.IssueApi.edit]], where a repeat re-applies a partial update against whatever the
  * resource has become. Neither hazard exists here, because these calls carry no body and set a state that is already
  * the state they set.
  *
  * The claim survives the optional parameters, and it is worth saying why. A `last_read_at` cursor, when the caller
  * sends one, is a fixed instant the caller chose: every attempt of one call sends the same cursor and therefore
  * describes the same set of threads, so a retry re-asserts the same statement rather than a later one. What
  * '''would''' have broken the claim is a cursor this library minted from the clock at each attempt, and it does not do
  * that — see [[markAllRead]]. A `to-status` names the end state and nothing else, so the same argument covers it.
  */
final class NotificationApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: NotificationApi.Attempt = NotificationApi.Attempt(this)

  /** Lists the authenticated user's notification threads — `GET /notifications`.
    *
    * '''Paging.''' The spec declares `page` and `limit` for this operation and declares `X-Total-Count` on its
    * response; it says nothing about a `Link` header, and no capture exists to settle it. Since
    * [[com.worxbend.codeberg4s.core.Pages]] reads `nextPage` from `rel="next"` and from nothing else, an instance that
    * sends no `Link` produces a page that reports itself as the last one however large `totalCount` is — the same
    * situation as [[com.worxbend.codeberg4s.repositories.publishing.ReleaseAssetApi]] describes for a listing without
    * paging, and the same remedy: compare [[com.worxbend.codeberg4s.paging.Page.totalCount]] with
    * [[com.worxbend.codeberg4s.paging.Page.size]] to decide whether to ask for another window. Never infer the end from
    * a short page: Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5).
    * A page past the end is `200` with `[]`.
    *
    * '''Failures.''' The group contract above. A `422` here most often means a malformed `since` or `before`.
    *
    * @param query
    *   the filters to apply; [[NotificationQuery.Empty]] asks for the instance's default, documented as unread and
    *   pinned threads
    * @param params
    *   which window to fetch, and how large
    */
  def list(query: NotificationQuery, params: PageParams): Future[Page[NotificationThread]] =
    pipeline.callPage(NotificationApi.listRequest(query, params), params)(using NotificationDecoders.threads)

  /** Marks the authenticated user's notification threads read — `PUT /notifications`, with the instance's defaults.
    *
    * '''No parameters, on purpose.''' Forgejo accepts `all`, `status-types`, `to-status` and `last_read_at` here; this
    * overload sends none of them and takes the instance's documented defaults, which are "the unread ones" and "mark
    * them read". The two of those a caller can usefully choose are on the other overload; `all` and `status-types`
    * describe a "mark everything pinned" operation that is a different thing from what this method's name promises, and
    * are not offered.
    *
    * '''The response body is deliberately ignored.''' The spec answers `205` with a list of the threads that changed,
    * but a `205` whose body is empty is also a success as far as [[com.worxbend.codeberg4s.core.StatusMapping]] is
    * concerned, and no capture exists to say which arrives when nothing needed changing. Decoding a body that may not
    * be there would turn a successful call into [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], so this
    * returns `Unit` and a caller who wants to know what changed re-reads [[list]].
    *
    * '''Retried.''' See the group note: repeating this is a no-op against an inbox that is already read.
    *
    * '''Failures.''' The group contract above.
    */
  def markAllRead(): Future[Unit] =
    markAllRead(None, None)

  /** Marks the authenticated user's notification threads read — `PUT /notifications`, with a cursor and a target
    * status.
    *
    * '''`last_read_at` is the cursor that makes this safe to call on a schedule.''' Forgejo marks only the threads
    * updated '''before''' it — "anything updated since this time will not be updated", in the spec's words — so a
    * caller who took the instant at which they last showed the inbox does not mark read a thread that changed while
    * they were looking. Rendered by [[com.worxbend.codeberg4s.codec.Timestamps.render]] in the RFC 3339 form Go parses;
    * a cursor Forgejo cannot parse is a `422` carrying a raw Go parse error (`docs/HAZARDS.md` §4). The instant is
    * '''the caller's''', fixed for every attempt of the call, which is what keeps the retry below honest; see the group
    * note.
    *
    * '''`to-status` names the end state.''' Forgejo's default is [[NotificationStatus.Read]]; a caller may instead pin
    * the threads or, less usefully, mark them unread. The values are the same three the `status-types` filter takes,
    * which is why the domain's [[NotificationStatus]] is reused rather than a second enum minted for one parameter.
    *
    * Absent, neither parameter is sent and the instance's default applies — an absent parameter and an empty one are
    * different requests, as everywhere in this library. The response body is ignored and the call is retried, both for
    * the reasons the no-argument overload gives.
    *
    * '''Failures.''' The group contract above; a `422` most often means a cursor Forgejo could not parse.
    *
    * @param lastReadAt
    *   mark only the threads updated before this instant, absent to mark them all
    * @param toStatus
    *   the status to mark the threads with, absent for the instance's default of `read`
    */
  def markAllRead(lastReadAt: Option[Instant], toStatus: Option[NotificationStatus]): Future[Unit] =
    pipeline.callUnit(NotificationApi.markAllReadRequest(lastReadAt, toStatus), RetryEligibility.AlwaysRetry)

  /** How many threads are unread — `GET /notifications/new`.
    *
    * Forgejo names the operation `notifyNewAvailable` and summarises it as an existence check, but the body it declares
    * is a count; [[UnreadCount]] takes the schema's side and offers [[UnreadCount.hasUnread]] for the other reading.
    *
    * '''Failures.''' The group contract above. A body without the `new` key is
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.new`, which is the signal that the spec was wrong
    * about this endpoint.
    */
  def unreadCount(): Future[UnreadCount] =
    pipeline.call(NotificationApi.unreadCountRequest, RetryEligibility.IdempotentOnly)(using
      NotificationDecoders.unreadCount)

  /** Reads one notification thread — `GET /notifications/threads/{id}`.
    *
    * '''Failures.''' The group contract above. The spec declares `403` and `404` for this operation specifically: a
    * thread belonging to somebody else is one or the other, and which one is not something a caller should branch on.
    *
    * @param id
    *   the thread's identifier, as it arrived on [[NotificationThread.id]]
    */
  def getThread(id: NotificationThreadId): Future[NotificationThread] =
    pipeline.call(NotificationApi.getThreadRequest(id), RetryEligibility.IdempotentOnly)(using
      NotificationDecoders.thread)

  /** Marks one notification thread read — `PATCH /notifications/threads/{id}`, with the instance's default status.
    *
    * '''Retried''', unlike every other `PATCH` in this library. The difference is real rather than stylistic:
    * [[com.worxbend.codeberg4s.issues.IssueApi.edit]] sends a partial update whose repeat can overwrite somebody else's
    * change, while this call sends no body at all and sets a state it may already have set. Forgejo's `to-status`
    * parameter is not sent by this overload, so the instance's documented default of `read` applies and the operation
    * stays the one this method's name promises; the other overload names a status.
    *
    * '''The response body is deliberately ignored''', for the reason [[markAllRead]] gives.
    *
    * '''Failures.''' The group contract above, plus the `403`/`404` pair [[getThread]] describes.
    */
  def markThreadRead(id: NotificationThreadId): Future[Unit] =
    pipeline.callUnit(NotificationApi.markThreadReadRequest(id, None), RetryEligibility.AlwaysRetry)

  /** Sets one notification thread's status — `PATCH /notifications/threads/{id}?to-status=…`.
    *
    * The same call as the one-argument [[markThreadRead]] with Forgejo's `to-status` named: [[NotificationStatus.Read]]
    * is what the instance would do anyway, [[NotificationStatus.Pinned]] keeps the thread at the top of the inbox, and
    * [[NotificationStatus.Unread]] puts it back. The spec declares the parameter as a free string with `read` as its
    * default; the three values are Forgejo's own `NotificationStatus` vocabulary, the same one `status-types` filters
    * by, so the domain enum is reused rather than a second one minted.
    *
    * Retried, and the body ignored, exactly as the one-argument overload — naming the end state changes neither
    * argument.
    *
    * '''Failures.''' As [[markThreadRead]].
    *
    * @param id
    *   the thread's identifier
    * @param toStatus
    *   the status to leave the thread in
    */
  def markThreadRead(id: NotificationThreadId, toStatus: NotificationStatus): Future[Unit] =
    pipeline.callUnit(NotificationApi.markThreadReadRequest(id, Some(toStatus)), RetryEligibility.AlwaysRetry)

  /** Lists the authenticated user's notification threads for one repository —
    * `GET /repos/{owner}/{repo}/notifications`.
    *
    * Still the caller's own inbox, narrowed: this is not a listing of everybody's notifications about the repository,
    * and a token that can read the repository still sees only its own threads.
    *
    * '''Paging.''' Exactly as [[list]] — same declared parameters, same `X-Total-Count`, same silence about `Link`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such repository" and "not visible to these
    * credentials"; Forgejo does not distinguish the two, on purpose.
    */
  def listRepository(
      owner: Owner,
      name: RepoName,
      query: NotificationQuery,
      params: PageParams,
  ): Future[Page[NotificationThread]] =
    pipeline.callPage(NotificationApi.listRepositoryRequest(owner, name, query, params), params)(using
      NotificationDecoders.threads)

  /** Marks the authenticated user's threads for one repository read — `PUT /repos/{owner}/{repo}/notifications`, with
    * the instance's defaults.
    *
    * The repository-scoped twin of the no-argument [[markAllRead]], with the same three properties: no parameters are
    * sent, the `205` body is ignored, and the call is retried because repeating it is a no-op.
    *
    * '''Failures.''' The group contract above.
    */
  def markRepositoryRead(owner: Owner, name: RepoName): Future[Unit] =
    markRepositoryRead(owner, name, None, None)

  /** Marks the authenticated user's threads for one repository read — `PUT /repos/{owner}/{repo}/notifications`, with a
    * cursor and a target status.
    *
    * The repository-scoped twin of the two-argument [[markAllRead]]: `last_read_at` and `to-status` mean exactly what
    * they mean there, and absent parameters are not sent.
    *
    * '''Failures.''' The group contract above; a `422` most often means a cursor Forgejo could not parse.
    *
    * @param lastReadAt
    *   mark only the threads updated before this instant, absent to mark them all
    * @param toStatus
    *   the status to mark the threads with, absent for the instance's default of `read`
    */
  def markRepositoryRead(
      owner: Owner,
      name: RepoName,
      lastReadAt: Option[Instant],
      toStatus: Option[NotificationStatus],
  ): Future[Unit] =
    pipeline.callUnit(
      NotificationApi.markRepositoryReadRequest(owner, name, lastReadAt, toStatus),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object NotificationApi:

  /** The stable operation id [[NotificationApi.list]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val ListOperation: String = "notifications.list"

  /** The stable operation id of [[NotificationApi.markAllRead]]. */
  val MarkAllReadOperation: String = "notifications.read"

  /** The stable operation id of [[NotificationApi.unreadCount]]. */
  val UnreadCountOperation: String = "notifications.new"

  /** The stable operation id of [[NotificationApi.getThread]]. */
  val GetThreadOperation: String = "notifications.threads.get"

  /** The stable operation id of [[NotificationApi.markThreadRead]]. */
  val MarkThreadReadOperation: String = "notifications.threads.read"

  /** The stable operation id of [[NotificationApi.listRepository]]. */
  val ListRepositoryOperation: String = "notifications.repository.list"

  /** The stable operation id of [[NotificationApi.markRepositoryRead]]. */
  val MarkRepositoryReadOperation: String = "notifications.repository.read"

  /** The typed rail of [[NotificationApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.notifications.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: NotificationApi)(using exec: Exec[Future]):

    /** [[NotificationApi.list]] with its failure as a value. */
    def list(
        query: NotificationQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[NotificationThread]]] =
      exec.attempt(rail.list(query, params))

    /** The no-argument [[NotificationApi.markAllRead]] with its failure as a value. */
    def markAllRead(): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.markAllRead())

    /** The two-argument [[NotificationApi.markAllRead]] with its failure as a value. */
    def markAllRead(
        lastReadAt: Option[Instant],
        toStatus: Option[NotificationStatus],
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.markAllRead(lastReadAt, toStatus))

    /** [[NotificationApi.unreadCount]] with its failure as a value. */
    def unreadCount(): Future[Either[CodebergError, UnreadCount]] =
      exec.attempt(rail.unreadCount())

    /** [[NotificationApi.getThread]] with its failure as a value. */
    def getThread(id: NotificationThreadId): Future[Either[CodebergError, NotificationThread]] =
      exec.attempt(rail.getThread(id))

    /** The one-argument [[NotificationApi.markThreadRead]] with its failure as a value. */
    def markThreadRead(id: NotificationThreadId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.markThreadRead(id))

    /** The two-argument [[NotificationApi.markThreadRead]] with its failure as a value. */
    def markThreadRead(id: NotificationThreadId, toStatus: NotificationStatus): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.markThreadRead(id, toStatus))

    /** [[NotificationApi.listRepository]] with its failure as a value. */
    def listRepository(
        owner: Owner,
        name: RepoName,
        query: NotificationQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[NotificationThread]]] =
      exec.attempt(rail.listRepository(owner, name, query, params))

    /** The two-argument [[NotificationApi.markRepositoryRead]] with its failure as a value. */
    def markRepositoryRead(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.markRepositoryRead(owner, name))

    /** The four-argument [[NotificationApi.markRepositoryRead]] with its failure as a value. */
    def markRepositoryRead(
        owner: Owner,
        name: RepoName,
        lastReadAt: Option[Instant],
        toStatus: Option[NotificationStatus],
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.markRepositoryRead(owner, name, lastReadAt, toStatus))

  /** Declared ahead of the requests that read it: an `object`'s `val`s initialise in source order, so a path defined
    * below a request built from it would be `null` when that request is constructed.
    */
  private val NotificationsPath: List[String] = List("notifications")

  private def listRequest(query: NotificationQuery, params: PageParams): CodebergRequest =
    read(
      ListOperation,
      NotificationsPath,
      NotificationQueries.notifications(query) ++ PagingQuery.window(params),
    )

  private def markAllReadRequest(lastReadAt: Option[Instant], toStatus: Option[NotificationStatus]): CodebergRequest =
    bodiless(MarkAllReadOperation, HttpMethod.Put, NotificationsPath, NotificationQueries.markRead(lastReadAt, toStatus))

  private val unreadCountRequest: CodebergRequest =
    read(UnreadCountOperation, NotificationsPath :+ "new", Nil)

  private def getThreadRequest(id: NotificationThreadId): CodebergRequest =
    read(GetThreadOperation, threadPath(id), Nil)

  private def markThreadReadRequest(id: NotificationThreadId, toStatus: Option[NotificationStatus]): CodebergRequest =
    bodiless(MarkThreadReadOperation, HttpMethod.Patch, threadPath(id), NotificationQueries.toStatus(toStatus))

  private def listRepositoryRequest(
      owner: Owner,
      name: RepoName,
      query: NotificationQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListRepositoryOperation,
      repositoryNotificationsPath(owner, name),
      NotificationQueries.notifications(query) ++ PagingQuery.window(params),
    )

  private def markRepositoryReadRequest(
      owner: Owner,
      name: RepoName,
      lastReadAt: Option[Instant],
      toStatus: Option[NotificationStatus],
  ): CodebergRequest =
    bodiless(
      MarkRepositoryReadOperation,
      HttpMethod.Put,
      repositoryNotificationsPath(owner, name),
      NotificationQueries.markRead(lastReadAt, toStatus),
    )

  private def threadPath(id: NotificationThreadId): List[String] =
    NotificationsPath ++ List("threads", id.value.toString)

  private def repositoryNotificationsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "notifications"
