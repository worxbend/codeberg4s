package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, remove}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** The people around a repository: who is watching it, who has starred it, and who may be assigned work on it.
  *
  * Reached as `client.repos.admin.watchers`. It is a group of its own rather than more methods on
  * [[RepositoryAdminApi]] because that class had grown past what a reader can hold in their head; the endpoints, the
  * models and the retry decisions are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryWatcherApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Two kinds of question==
  *
  * [[subscription]], [[watch]] and [[unwatch]] are about the '''authenticated account''' — what it has chosen to be
  * notified about. [[assignees]], [[reviewers]], [[stargazers]] and [[subscribers]] are about '''everybody else''', and
  * each returns [[com.worxbend.codeberg4s.users.User]] values. The two sit together because they read the same
  * relationships from opposite ends.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope or the account lacks the
  *     permission. `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 records
  *     Forgejo using `400` where a reader would expect `422`.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. [[watch]] and [[unwatch]] use
  * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]: each names one repository and one account, sets a
  * flag rather than creating a row, and so leaves the same state after N attempts as after one.
  *
  * ==Evidence==
  *
  * '''Every model this group declares is derived from `spec/swagger.v1.json`, not from a captured response.''' The
  * harvest behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no
  * fixture exists for any of them. Where a shape is asserted in a test, the payload was written by hand to match the
  * spec's definition — it is not evidence that Forgejo sends exactly this.
  */

final class RepositoryWatcherApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryWatcherApi.Attempt = RepositoryWatcherApi.Attempt(this)

  /** Reads whether the authenticated account watches the repository — `GET /repos/{owner}/{repo}/subscription`.
    *
    * '''Not watching is a `404`, not a `false`.''' The spec says so in as many words, and it is the same status a
    * repository the caller cannot see produces — so this method '''cannot''' be used as a "do I watch this?" predicate
    * without also treating "no such repository" as "not watching". Read [[WatchStatus]] before relying on it.
    *
    * '''Failures.''' The group contract above, with the `404` caveat above.
    */
  def subscription(owner: Owner, name: RepoName): Future[WatchStatus] =
    pipeline.call(RepositoryWatcherApi.subscriptionRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.watchStatus)

  /** Starts watching the repository — `PUT /repos/{owner}/{repo}/subscription`.
    *
    * '''Retried.''' This meets the bar: the request names the repository in its path and the account in its token, sets
    * that one subscription to "watching", creates nothing, and carries no body that could differ between attempts. The
    * end state after N attempts is the state after one, and unlike a delete there is no `404`-after-success cost —
    * repeating it answers `200` with the same [[WatchStatus]].
    *
    * '''Failures.''' The group contract above.
    */
  def watch(owner: Owner, name: RepoName): Future[WatchStatus] =
    pipeline.call(RepositoryWatcherApi.watchRequest(owner, name), RetryEligibility.AlwaysRetry)(using
      RepositoryAdminDecoders.watchStatus)

  /** Stops watching the repository — `DELETE /repos/{owner}/{repo}/subscription`.
    *
    * '''Retried''', for the reason [[watch]] gives and with one difference: what is being removed is the subscription
    * of the account holding the token, which is not a name anybody else can take. A repeat therefore cannot reach a
    * different subject the way [[deleteBranch]] can, and Forgejo answers `204` whether or not there was a subscription
    * to remove — so there is not even a `404`-after-success cost.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above.
    */
  def unwatch(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryWatcherApi.unwatchRequest(owner, name), RetryEligibility.AlwaysRetry)

  // --- people ---------------------------------------------------------------

  /** Lists the accounts that may be assigned to an issue — `GET /repos/{owner}/{repo}/assignees`.
    *
    * Everyone with write access. '''Not paged''': the endpoint declares no `page` or `limit`, and answers the whole
    * set, which is why this returns a `Vector` and not a [[com.worxbend.codeberg4s.paging.Page]].
    *
    * '''Failures.''' The group contract above.
    */
  def assignees(owner: Owner, name: RepoName): Future[Vector[User]] =
    pipeline.call(RepositoryWatcherApi.assigneesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.users)

  /** Lists the accounts that may be asked to review a pull request — `GET /repos/{owner}/{repo}/reviewers`.
    *
    * '''Not the same set as [[assignees]]''', despite both being "people with access": Forgejo computes reviewers from
    * the branch protection rules as well as from collaboration, so the two lists can differ on the same repository.
    * Also not paged.
    *
    * '''Failures.''' The group contract above.
    */
  def reviewers(owner: Owner, name: RepoName): Future[Vector[User]] =
    pipeline.call(RepositoryWatcherApi.reviewersRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.users)

  /** Lists the accounts that starred the repository — `GET /repos/{owner}/{repo}/stargazers`.
    *
    * '''Paging.''' As [[pushMirrors]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def stargazers(owner: Owner, name: RepoName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(RepositoryWatcherApi.stargazersRequest(owner, name, params), params)(using
      RepositoryAdminDecoders.users)

  /** Lists the accounts that watch the repository — `GET /repos/{owner}/{repo}/subscribers`.
    *
    * The other side of [[subscription]]: this is everyone watching, that is whether one particular account does.
    *
    * '''Paging.''' As [[pushMirrors]].
    *
    * '''Failures.''' The group contract above.
    */
  def subscribers(owner: Owner, name: RepoName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(RepositoryWatcherApi.subscribersRequest(owner, name, params), params)(using
      RepositoryAdminDecoders.users)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryWatcherApi:

  /** The stable operation id of [[RepositoryWatcherApi.subscription]]. */
  val GetSubscriptionOperation: String = "repos.admin.subscription.get"

  /** The stable operation id of [[RepositoryWatcherApi.watch]]. */
  val WatchOperation: String = "repos.admin.subscription.watch"

  /** The stable operation id of [[RepositoryWatcherApi.unwatch]]. */
  val UnwatchOperation: String = "repos.admin.subscription.unwatch"

  /** The stable operation id of [[RepositoryWatcherApi.assignees]]. */
  val ListAssigneesOperation: String = "repos.admin.assignees.list"

  /** The stable operation id of [[RepositoryWatcherApi.reviewers]]. */
  val ListReviewersOperation: String = "repos.admin.reviewers.list"

  /** The stable operation id of [[RepositoryWatcherApi.stargazers]]. */
  val ListStargazersOperation: String = "repos.admin.stargazers.list"

  /** The stable operation id of [[RepositoryWatcherApi.subscribers]]. */
  val ListSubscribersOperation: String = "repos.admin.subscribers.list"

  /** The typed rail of [[RepositoryWatcherApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.admin.watchers.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryWatcherApi)(using exec: Exec[Future]):

    /** [[RepositoryWatcherApi.subscription]] with its failure as a value. */
    def subscription(owner: Owner, name: RepoName): Future[Either[CodebergError, WatchStatus]] =
      exec.attempt(rail.subscription(owner, name))

    /** [[RepositoryWatcherApi.watch]] with its failure as a value. */
    def watch(owner: Owner, name: RepoName): Future[Either[CodebergError, WatchStatus]] =
      exec.attempt(rail.watch(owner, name))

    /** [[RepositoryWatcherApi.unwatch]] with its failure as a value. */
    def unwatch(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unwatch(owner, name))

    /** [[RepositoryWatcherApi.assignees]] with its failure as a value. */
    def assignees(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[User]]] =
      exec.attempt(rail.assignees(owner, name))

    /** [[RepositoryWatcherApi.reviewers]] with its failure as a value. */
    def reviewers(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[User]]] =
      exec.attempt(rail.reviewers(owner, name))

    /** [[RepositoryWatcherApi.stargazers]] with its failure as a value. */
    def stargazers(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.stargazers(owner, name, params))

    /** [[RepositoryWatcherApi.subscribers]] with its failure as a value. */
    def subscribers(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.subscribers(owner, name, params))

  private def subscriptionRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(GetSubscriptionOperation, subscriptionPath(owner, name), Nil)

  /** The watch `PUT` carries no body. Forgejo declares none, and sending `{}` would be a body the endpoint never
    * defined.
    */
  private def watchRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(WatchOperation, HttpMethod.Put, subscriptionPath(owner, name))

  private def unwatchRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(UnwatchOperation, subscriptionPath(owner, name))

  private def assigneesRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListAssigneesOperation, RepositoryRequests.repositoryPath(owner, name) :+ "assignees", Nil)

  private def reviewersRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListReviewersOperation, RepositoryRequests.repositoryPath(owner, name) :+ "reviewers", Nil)

  private def stargazersRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(
      ListStargazersOperation,
      RepositoryRequests.repositoryPath(owner, name) :+ "stargazers",
      PagingQuery.window(params)
    )

  private def subscribersRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(
      ListSubscribersOperation,
      RepositoryRequests.repositoryPath(owner, name) :+ "subscribers",
      PagingQuery.window(params)
    )

  private def subscriptionPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "subscription"
