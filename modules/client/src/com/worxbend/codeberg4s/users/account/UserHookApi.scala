package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.hooks.wire.HookOptionDto
import com.worxbend.codeberg4s.repositories.hooks.{CreateHook, EditHook, HookId, Webhook}
import com.worxbend.codeberg4s.users.account.wire.AccountQueries
import com.worxbend.codeberg4s.{CodebergError, HttpMethod}

import scala.concurrent.Future

/** The webhooks the authenticated account owns — `/user/hooks`.
  *
  * Reached as `client.users.account.hooks`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserHookApi.attempt]]
  * never fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This is the repository hook surface, scoped to an account==
  *
  * `/user/hooks` answers the same `Hook` model and takes the same `CreateHookOption` and `EditHookOption` bodies as
  * `/repos/{owner}/{repo}/hooks`, so this class reuses [[com.worxbend.codeberg4s.repositories.hooks.Webhook]],
  * [[com.worxbend.codeberg4s.repositories.hooks.CreateHook]], [[com.worxbend.codeberg4s.repositories.hooks.EditHook]],
  * [[com.worxbend.codeberg4s.repositories.hooks.HookConfig]] and
  * [[com.worxbend.codeberg4s.repositories.hooks.HookSecret]] rather than defining twins of them. Only the path differs,
  * and what a hook '''covers''' differs: an account hook fires for events on every repository the account owns, present
  * and future, which makes creating one a broader act than creating a repository's own.
  *
  * '''There is no test route here.''' `spec/swagger.v1.json` declares `POST /repos/{owner}/{repo}/hooks/{id}/tests` and
  * nothing equivalent under `/user`, so a caller cannot ask the instance to deliver a sample payload to an account
  * hook. This class does not offer a method that would always answer `404`.
  *
  * ==The credential discipline is inherited, and it is structural==
  *
  * A hook's signing secret and its `Authorization` header are credentials.
  * [[com.worxbend.codeberg4s.repositories.hooks.HookConfig]] refuses to hold either — its constructor is private and
  * drops those keys — so a secret cannot arrive in a config a caller built, and cannot be read back out of one the
  * instance sent. Both travel in a [[com.worxbend.codeberg4s.repositories.hooks.HookSecret]], which masks itself, and
  * are merged into the request body at the last moment by
  * [[com.worxbend.codeberg4s.repositories.hooks.wire.HookOptionDto]]. Reusing those types rather than forking them is
  * what makes that guarantee hold here too, for free.
  *
  * ==Evidence==
  *
  * '''Every payload this class reads is derived from `spec/swagger.v1.json`, not from a captured response'''; see
  * [[com.worxbend.codeberg4s.repositories.hooks.Webhook]], whose model this is.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures:
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
  *     was rejected — every path in this group is `/user/…` and has no anonymous reading — and `403` when the token
  *     lacks the scope. `404` is what a hook id belonging to another account produces, even though the spec declares no
  *     `404` for these operations at all: the spec's response list is incomplete here, and treating its silence as a
  *     promise would be exactly the guesswork `docs/HAZARDS.md` warns against.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, which for this group is `$.id` or `$[n].id`.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here: every argument is
  * an already-validated type.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class UserHookApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserHookApi.Attempt = UserHookApi.Attempt(this)

  /** Lists the account's webhooks — `GET /user/hooks`.
    *
    * '''The spec declares this response as `HookListWithoutPagination`''', a name that says outright that it carries no
    * paging headers — while the operation still declares `page` and `limit` parameters. Both are sent, because a window
    * a caller asked for must reach the instance, and the result is still a [[com.worxbend.codeberg4s.paging.Page]]: the
    * window is what was requested, and [[com.worxbend.codeberg4s.paging.Page.totalCount]] and
    * [[com.worxbend.codeberg4s.paging.Page.nextPage]] are simply absent when the instance sends no `X-Total-Count` and
    * no `rel="next"`. Absent means unknown, never zero and never "this is the last page" — a caller walking pages must
    * stop on an empty page rather than on `isLast`.
    *
    * '''Failures.''' The group contract above.
    */
  def list(params: PageParams): Future[Page[Webhook]] =
    pipeline.callPage(UserHookApi.listRequest(params), params)(using UserAccountDecoders.webhooks)

  /** Reads one of the account's webhooks — `GET /user/hooks/{id}`.
    *
    * '''No credential comes back.''' Neither the signing secret nor the `Authorization` header is readable; see the
    * class note and [[com.worxbend.codeberg4s.repositories.hooks.HookConfig]].
    *
    * '''Failures.''' The group contract above.
    */
  def get(id: HookId): Future[Webhook] =
    pipeline.call(UserHookApi.getRequest(id), RetryEligibility.IdempotentOnly)(using UserAccountDecoders.webhook)

  /** Creates a webhook on the account — `POST /user/hooks`.
    *
    * '''Never retried.''' Nothing in the request identifies it: hook URLs are not unique, Forgejo offers no idempotency
    * key, and a repeat produces a second hook that will deliver every event twice. A transport failure therefore leaves
    * the caller unsure whether a hook exists, which [[list]] resolves.
    *
    * '''This hook covers every repository the account owns''', including ones created after it; see the class note.
    *
    * '''Answers `201`''' with the hook as stored, credentials stripped.
    *
    * '''Failures.''' The group contract above.
    */
  def create(command: CreateHook): Future[Webhook] =
    pipeline.call(UserHookApi.createRequest(command), RetryEligibility.Never)(using UserAccountDecoders.webhook)

  /** Edits one of the account's webhooks — `PATCH /user/hooks/{id}`.
    *
    * '''Retried''', and the argument is the one earlier waves set the bar with: the request names one instance-wide
    * identifier that Forgejo never reuses — [[com.worxbend.codeberg4s.repositories.hooks.HookId]] is a database row id
    * — and states the value it wants each key it mentions to have. Applying that twice leaves the hook exactly where
    * applying it once would, and creates nothing. Unlike a delete there is not even a `404` to explain afterwards: a
    * retry after a lost success simply reports the hook in the state that was asked for.
    *
    * '''What the command does not mention is left alone'''; see [[com.worxbend.codeberg4s.repositories.hooks.EditHook]]
    * for how "unsubscribe from everything" is spelled differently from "leave the subscriptions alone".
    *
    * '''Answers `200`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def edit(id: HookId, command: EditHook): Future[Webhook] =
    pipeline.call(UserHookApi.editRequest(id, command), RetryEligibility.AlwaysRetry)(using UserAccountDecoders.webhook)

  /** Deletes one of the account's webhooks — `DELETE /user/hooks/{id}`.
    *
    * '''Retried''', because deleting a resource named by an identifier the server never reuses is idempotent: doing it
    * twice leaves the account where doing it once would, and creates nothing. The one cost: if the first attempt
    * succeeded and its response was lost, the retry addresses something that no longer exists and answers `404`. A
    * `404` from a delete therefore means "it is gone", not necessarily "it was never there".
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def delete(id: HookId): Future[Unit] =
    pipeline.callUnit(UserHookApi.deleteRequest(id), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object UserHookApi:

  /** The stable operation id of [[UserHookApi.list]]. Safe to alert on. */
  val ListOperation: String = "users.account.hooks.list"

  /** The stable operation id of the single-hook read on [[UserHookApi]]. */
  val GetOperation: String = "users.account.hooks.get"

  /** The stable operation id of [[UserHookApi.create]]. */
  val CreateOperation: String = "users.account.hooks.create"

  /** The stable operation id of [[UserHookApi.edit]]. */
  val EditOperation: String = "users.account.hooks.edit"

  /** The stable operation id of [[UserHookApi.delete]]. */
  val DeleteOperation: String = "users.account.hooks.delete"

  /** The typed rail of [[UserHookApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.account.hooks.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: UserHookApi)(using exec: Exec[Future]):

    /** [[UserHookApi.list]] with its failure as a value. */
    def list(params: PageParams): Future[Either[CodebergError, Page[Webhook]]] =
      exec.attempt(rail.list(params))

    /** [[UserHookApi.get]] with its failure as a value. */
    def get(id: HookId): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.get(id))

    /** [[UserHookApi.create]] with its failure as a value. */
    def create(command: CreateHook): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.create(command))

    /** [[UserHookApi.edit]] with its failure as a value. */
    def edit(id: HookId, command: EditHook): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.edit(id, command))

    /** [[UserHookApi.delete]] with its failure as a value. */
    def delete(id: HookId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(id))

  private def listRequest(params: PageParams): CodebergRequest =
    read(ListOperation, hooksPath, AccountQueries.paging(params))

  private def getRequest(id: HookId): CodebergRequest =
    read(GetOperation, hookPath(id), Nil)

  private def createRequest(command: CreateHook): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, hooksPath, HookOptionDto.renderCreate(command))

  private def editRequest(id: HookId, command: EditHook): CodebergRequest =
    write(EditOperation, HttpMethod.Patch, hookPath(id), HookOptionDto.renderEdit(command))

  private def deleteRequest(id: HookId): CodebergRequest =
    remove(DeleteOperation, hookPath(id))

  private def hooksPath: List[String] =
    AccountRequests.path("hooks")

  private def hookPath(id: HookId): List[String] =
    hooksPath :+ id.value.toString
