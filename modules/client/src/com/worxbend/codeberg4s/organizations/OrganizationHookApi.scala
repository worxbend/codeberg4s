package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergRequest.bodiless
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.CodebergRequest.write
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.organizations.wire.OrganizationQueries
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.hooks.CreateHook
import com.worxbend.codeberg4s.repositories.hooks.EditHook
import com.worxbend.codeberg4s.repositories.hooks.HookId
import com.worxbend.codeberg4s.repositories.hooks.Webhook
import com.worxbend.codeberg4s.repositories.hooks.wire.HookOptionDto

import scala.concurrent.Future

/** An organisation's webhooks — the deliveries Forgejo makes when anything in the organisation happens.
  *
  * Reached as `client.organizations.hooks`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on
  * [[OrganizationHookApi.attempt]] never fail and return an `Either` instead. The typed rail is derived from this one
  * by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This is the repository hook model, not a second one==
  *
  * `/orgs/{org}/hooks` and `/repos/{owner}/{repo}/hooks` serve Forgejo's single `Hook` definition, take the single
  * `CreateHookOption` and `EditHookOption`, and allocate identifiers from one table. So this class reuses
  * [[com.worxbend.codeberg4s.repositories.hooks.Webhook]], [[com.worxbend.codeberg4s.repositories.hooks.HookConfig]],
  * [[com.worxbend.codeberg4s.repositories.hooks.HookEvent]], [[com.worxbend.codeberg4s.repositories.hooks.HookSecret]]
  * and [[com.worxbend.codeberg4s.repositories.hooks.HookId]] unchanged, and renders its bodies with the same
  * `HookOptionDto`. `docs/LEDGER.md` calls a forked copy of a shared model a review-blocking defect; a webhook model
  * spelled twice would be the fourth copy of one concept in this codebase.
  *
  * The '''scope''' differs and nothing else: an organisation hook fires for events in every repository the organisation
  * owns, including repositories created after the hook was.
  *
  * ==Secrets are write-only, by construction==
  *
  * A webhook carries two credentials and neither one comes back. [[create]] and [[edit]] take a
  * [[com.worxbend.codeberg4s.repositories.hooks.HookSecret]], which masks itself in every rendering path;
  * [[com.worxbend.codeberg4s.repositories.hooks.HookConfig]] refuses to hold either credential in either direction, so
  * nothing an instance echoes back can reach a caller, a log line or a
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] snippet. That is a property of the types rather than a rule
  * this class remembers.
  *
  * ==Evidence==
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The harvest behind
  * `modules/codec/test/resources/golden` was anonymous and every route here needs a token, so no fixture exists for any
  * of them. Payloads asserted in the suites were written by hand to match the spec's `Hook` definition and are not
  * evidence that Forgejo sends exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than on each method.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with `404` when the organisation does not exist '''or''' is not
  *     visible to the configured credentials — Forgejo does not distinguish the two, on purpose — and `404` again for a
  *     hook that is not one of this organisation's. `401` without a token and `403` when the token lacks the scope.
  *     `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 captured Forgejo using
  *     both, so a caller checking only for `422` will miss half of them.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, at its position for a listing.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here: every argument is
  * an already-validated type.
  *
  * ==Retries==
  *
  * Reads are [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. [[create]] is never retried. [[edit]]
  * and [[delete]] are, and the argument is the same one
  * [[com.worxbend.codeberg4s.repositories.hooks.RepositoryHookApi]] makes: both name the hook by a
  * [[com.worxbend.codeberg4s.repositories.hooks.HookId]], a database row id Forgejo never reuses. The `{org}` segment
  * of the path does not weaken that even though [[OrgName]] is a handle [[OrganizationApi.rename]] can move: a hook id
  * belongs to exactly one organisation, so a stale handle can only turn the call into a `404` and can never make it act
  * on a different hook.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class OrganizationHookApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationHookApi.Attempt = OrganizationHookApi.Attempt(this)

  /** Lists an organisation's webhooks — `GET /orgs/{org}/hooks`.
    *
    * '''Paged, despite the response's name.''' The spec types the `200` as `HookListWithoutPagination` and then
    * declares `page` and `limit` on the very same operation. The parameters are what decide, so both are sent and the
    * result is a [[com.worxbend.codeberg4s.paging.Page]]. Where the collection ends is the `Link` header's `rel="next"`
    * and never a short page — Forgejo clamps `limit` to its own maximum while echoing the requested value
    * (`docs/HAZARDS.md` §5). A page past the end is `200` with `[]`, not a `404`.
    *
    * '''No secret is in the result'''; see the class note.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many hooks it may hold
    */
  def list(org: OrgName, params: PageParams): Future[Page[Webhook]] =
    pipeline.callPage(OrganizationHookApi.listRequest(org, params), params)(using OrganizationDecoders.webhooks)

  /** Reads one webhook — `GET /orgs/{org}/hooks/{id}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such hook in this organisation" and "no such
    * organisation", which the response alone cannot tell apart.
    *
    * @param org
    *   the organisation handle
    * @param id
    *   the hook's instance-wide identifier
    */
  def get(org: OrgName, id: HookId): Future[Webhook] =
    pipeline.call(OrganizationHookApi.getRequest(org, id), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.webhook)

  /** Creates a webhook on the organisation — `POST /orgs/{org}/hooks`.
    *
    * '''Never retried.''' Nothing in the request identifies it: hook URLs are not unique, Forgejo offers no idempotency
    * key, and a repeat therefore creates a '''second''' hook pointing at the same endpoint — which then receives every
    * delivery from every repository in the organisation twice. A transport failure leaves the caller genuinely unsure
    * whether a hook exists, which is the honest state of affairs and better than two; [[list]] resolves it.
    *
    * '''Answers `201` with the created hook''', including the identifier every other route here needs.
    *
    * '''The scope is the whole organisation.''' A hook created here fires for repositories that do not exist yet.
    *
    * '''Failures.''' The group contract above. A `422` is what Forgejo answers for a config it will not accept — most
    * often a `url` it cannot parse or a `type` it does not implement.
    *
    * @param org
    *   the organisation handle
    * @param command
    *   the hook to create; its secret, if any, travels once and never comes back
    */
  def create(org: OrgName, command: CreateHook): Future[Webhook] =
    pipeline.call(OrganizationHookApi.createRequest(org, command), RetryEligibility.Never)(using
      OrganizationDecoders.webhook)

  /** Edits a webhook — `PATCH /orgs/{org}/hooks/{id}`.
    *
    * '''Retried''', on the argument the class note states: the request names one hook by an identifier Forgejo never
    * reuses and assigns a stated value to each key it mentions, so applying it twice leaves the hook exactly where
    * applying it once would and creates nothing. The one cost is the usual one — if the first attempt succeeded and its
    * response was lost, the retry addresses a hook already in the requested state and simply reports it again, so
    * unlike a delete there is not even a `404` to explain.
    *
    * '''What the command does not mention is left alone'''; see [[com.worxbend.codeberg4s.repositories.hooks.EditHook]]
    * for how "unsubscribe from everything" is spelled differently from "leave the subscriptions alone".
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param id
    *   the hook's instance-wide identifier
    * @param command
    *   what to change; an empty command is a well-formed request that changes nothing
    */
  def edit(org: OrgName, id: HookId, command: EditHook): Future[Webhook] =
    pipeline.call(OrganizationHookApi.editRequest(org, id, command), RetryEligibility.AlwaysRetry)(using
      OrganizationDecoders.webhook)

  /** Deletes a webhook — `DELETE /orgs/{org}/hooks/{id}`.
    *
    * '''Retried''', because deleting a resource named by an identifier the server never reuses is idempotent: doing it
    * twice leaves the instance where doing it once would have, and nothing is created. The cost a caller has to know:
    * if the first attempt succeeded and its response was lost, the retry addresses something that no longer exists and
    * answers `404`. A `404` from this call therefore means "it is gone", not necessarily "it was never there".
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param id
    *   the hook's instance-wide identifier
    */
  def delete(org: OrgName, id: HookId): Future[Unit] =
    pipeline.callUnit(OrganizationHookApi.deleteRequest(org, id), RetryEligibility.AlwaysRetry)

/** The requests [[OrganizationHookApi]] issues, its operation ids, and its typed rail. */
object OrganizationHookApi:

  /** The stable operation id of [[OrganizationHookApi.list]]. Safe to alert on. */
  val ListOperation: String = "orgs.hooks.list"

  /** The stable operation id of the single-hook read on [[OrganizationHookApi]]. */
  val GetOperation: String = "orgs.hooks.get"

  /** The stable operation id of [[OrganizationHookApi.create]]. */
  val CreateOperation: String = "orgs.hooks.create"

  /** The stable operation id of [[OrganizationHookApi.edit]]. */
  val EditOperation: String = "orgs.hooks.edit"

  /** The stable operation id of [[OrganizationHookApi.delete]]. */
  val DeleteOperation: String = "orgs.hooks.delete"

  /** The path segment the organisation hook collection sits under. */
  private val HooksSegment: String = "hooks"

  /** The typed rail of [[OrganizationHookApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.organizations.hooks.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationHookApi)(using exec: Exec[Future]):

    /** [[OrganizationHookApi.list]] with its failure as a value. */
    def list(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[Webhook]]] =
      exec.attempt(rail.list(org, params))

    /** The single-hook read on [[OrganizationHookApi]], with its failure as a value. */
    def get(org: OrgName, id: HookId): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.get(org, id))

    /** [[OrganizationHookApi.create]] with its failure as a value. */
    def create(org: OrgName, command: CreateHook): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.create(org, command))

    /** [[OrganizationHookApi.edit]] with its failure as a value. */
    def edit(org: OrgName, id: HookId, command: EditHook): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.edit(org, id, command))

    /** [[OrganizationHookApi.delete]] with its failure as a value. */
    def delete(org: OrgName, id: HookId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(org, id))

  private def listRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(ListOperation, hooksPath(org), OrganizationQueries.paging(params))

  private def getRequest(org: OrgName, id: HookId): CodebergRequest =
    read(GetOperation, hookPath(org, id), Nil)

  private def createRequest(org: OrgName, command: CreateHook): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, hooksPath(org), HookOptionDto.renderCreate(command))

  private def editRequest(org: OrgName, id: HookId, command: EditHook): CodebergRequest =
    write(EditOperation, HttpMethod.Patch, hookPath(org, id), HookOptionDto.renderEdit(command))

  private def deleteRequest(org: OrgName, id: HookId): CodebergRequest =
    bodiless(DeleteOperation, HttpMethod.Delete, hookPath(org, id))

  private def hooksPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ HooksSegment

  private def hookPath(org: OrgName, id: HookId): List[String] =
    hooksPath(org) :+ id.value.toString
