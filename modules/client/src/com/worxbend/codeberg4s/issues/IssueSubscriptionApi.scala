package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{empty, read}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** Who is following an issue, and whether the authenticated account is one of them.
  *
  * Reached as `client.issues.subscriptions`. Four operations: list the subscribers, check the authenticated account's
  * own status, and subscribe or unsubscribe a named account.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueSubscriptionApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * The `User` model the subscriber listing returns is captured many times over in
  * `modules/codec/test/resources/golden`. The `WatchInfo` object [[check]] returns is '''not''': it describes the
  * authenticated account and the harvest was anonymous, so [[com.worxbend.codeberg4s.issues.wire.IssueSubscriptionDto]]
  * is derived from `spec/swagger.v1.json`.
  *
  * ==Subscribing somebody else needs to be an administrator==
  *
  * `spec/swagger.v1.json` declares `304` on both writes with the words "user can only subscribe itself if he is no
  * admin". `304` is '''not''' a `2xx`, so [[com.worxbend.codeberg4s.core.StatusMapping]] turns it into
  * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `304` — a failure on both rails, and a surprising status
  * to see in one. It means the account named in the path is not the token's own and the token is not an administrator.
  *
  * ==Neither write has a body==
  *
  * The account is a path segment and there is nothing else to say, so both are sent with
  * [[com.worxbend.codeberg4s.core.RequestBody.Empty]] rather than with no body at all — some Forgejo `PUT` routes
  * insist on a well-formed empty body, and this is the shape [[com.worxbend.codeberg4s.core.RequestBody.Empty]] exists
  * for.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the issue or the account
  *     does not exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the
  *     two, on purpose — `401` when a token was required and none was sent, `403` when the token lacks the scope, and
  *     `304` on the two writes for the reason above.
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
final class IssueSubscriptionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueSubscriptionApi.Attempt = IssueSubscriptionApi.Attempt(this)

  /** Lists the accounts following an issue — `GET /repos/{owner}/{repo}/issues/{index}/subscriptions`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above.
    */
  def list(owner: Owner, name: RepoName, number: IssueNumber, params: PageParams): Future[Page[User]] =
    pipeline.callPage(IssueSubscriptionApi.listRequest(owner, name, number, params), params)(using IssueDecoders.users)

  /** Reports whether the authenticated account follows an issue —
    * `GET /repos/{owner}/{repo}/issues/{index}/subscriptions/check`.
    *
    * '''About the token, not about a named account.''' There is no way to ask this question about somebody else;
    * [[list]] is the closest thing, and it answers a different question.
    *
    * '''`subscribed` and `ignored` are not opposites'''; see [[IssueSubscription]].
    *
    * '''Failures.''' The group contract above.
    */
  def check(owner: Owner, name: RepoName, number: IssueNumber): Future[IssueSubscription] =
    pipeline.call(IssueSubscriptionApi.checkRequest(owner, name, number), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.subscription)

  /** Subscribes an account to an issue — `PUT /repos/{owner}/{repo}/issues/{index}/subscriptions/{user}`.
    *
    * '''Retried''', because the request names one issue and one account and asks for an absolute end state — that
    * account follows that issue. Doing it twice leaves the instance exactly where doing it once would, and nothing is
    * created that a second attempt could duplicate. A lost success costs nothing on the retry either: the spec declares
    * `201` for "successfully subscribed" and `200` for "already subscribed", and both are success.
    *
    * '''No body either way''', so there is nothing to return; [[check]] reads back the token's own status.
    *
    * '''Failures.''' The group contract above, including the `304` that subscribing somebody else produces for a
    * non-administrator.
    *
    * @param login
    *   the account to subscribe. Must be the token's own unless the token is an administrator
    */
  def subscribe(owner: Owner, name: RepoName, number: IssueNumber, login: Owner): Future[Unit] =
    pipeline.callUnit(
      IssueSubscriptionApi.subscribeRequest(owner, name, number, login),
      RetryEligibility.AlwaysRetry,
    )

  /** Unsubscribes an account from an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/subscriptions/{user}`.
    *
    * '''Retried''', for the reason [[subscribe]] gives, and with the same absence of a penalty for a lost success: the
    * spec declares `201` for "successfully unsubscribed" and `200` for "already unsubscribed", so unlike most deletes
    * in this library a repeat does not turn into a `404`.
    *
    * '''No body either way''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above, including the `304`.
    *
    * @param login
    *   the account to unsubscribe. Must be the token's own unless the token is an administrator
    */
  def unsubscribe(owner: Owner, name: RepoName, number: IssueNumber, login: Owner): Future[Unit] =
    pipeline.callUnit(
      IssueSubscriptionApi.unsubscribeRequest(owner, name, number, login),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueSubscriptionApi:

  /** The stable operation id of [[IssueSubscriptionApi.list]]. Safe to alert on. */
  val ListOperation: String = "issues.subscriptions.list"

  /** The stable operation id of [[IssueSubscriptionApi.check]]. */
  val CheckOperation: String = "issues.subscriptions.check"

  /** The stable operation id of [[IssueSubscriptionApi.subscribe]]. */
  val SubscribeOperation: String = "issues.subscriptions.subscribe"

  /** The stable operation id of [[IssueSubscriptionApi.unsubscribe]]. */
  val UnsubscribeOperation: String = "issues.subscriptions.unsubscribe"

  /** The typed rail of [[IssueSubscriptionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.issues.subscriptions.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueSubscriptionApi)(using exec: Exec[Future]):

    /** [[IssueSubscriptionApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.list(owner, name, number, params))

    /** [[IssueSubscriptionApi.check]] with its failure as a value. */
    def check(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
    ): Future[Either[CodebergError, IssueSubscription]] =
      exec.attempt(rail.check(owner, name, number))

    /** [[IssueSubscriptionApi.subscribe]] with its failure as a value. */
    def subscribe(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        login: Owner,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.subscribe(owner, name, number, login))

    /** [[IssueSubscriptionApi.unsubscribe]] with its failure as a value. */
    def unsubscribe(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        login: Owner,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unsubscribe(owner, name, number, login))

  private def listRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListOperation, subscriptionsPath(owner, name, number), PagingQuery.window(params))

  private def checkRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    read(CheckOperation, subscriptionsPath(owner, name, number) :+ "check", Nil)

  private def subscribeRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      login: Owner,
  ): CodebergRequest =
    empty(SubscribeOperation, HttpMethod.Put, subscriptionsPath(owner, name, number) :+ login.value)

  private def unsubscribeRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      login: Owner,
  ): CodebergRequest =
    empty(UnsubscribeOperation, HttpMethod.Delete, subscriptionsPath(owner, name, number) :+ login.value)

  private def subscriptionsPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "subscriptions"
