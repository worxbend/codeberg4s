package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.issues.wire.{CreateLabelOptionDto, EditLabelOptionDto}
import com.worxbend.codeberg4s.issues.{CreateLabel, EditLabel, Label, LabelId}
import com.worxbend.codeberg4s.organizations.wire.OrganizationQueries
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod}

import scala.concurrent.Future

/** An organisation's labels — the shared label set its repositories may draw from.
  *
  * Reached as `client.organizations.labels`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on
  * [[OrganizationLabelApi.attempt]] never fail and return an `Either` instead. The typed rail is derived from this one
  * by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This is the repository label model, and there is a capture proving it==
  *
  * `golden/organization/org-labels-list.json` is a real, anonymous `GET /orgs/forgejo/labels?page=1&limit=3`, and all
  * seven keys of every element are the seven keys of `golden/issue/labels-repo.json`. So this class reuses
  * [[com.worxbend.codeberg4s.issues.Label]], [[com.worxbend.codeberg4s.issues.LabelId]],
  * [[com.worxbend.codeberg4s.issues.LabelColor]], [[com.worxbend.codeberg4s.issues.CreateLabel]] and
  * [[com.worxbend.codeberg4s.issues.EditLabel]] unchanged, and renders its bodies with the same option DTOs.
  * `docs/LEDGER.md` calls a forked copy of a shared model a review-blocking defect.
  *
  * ==What an organisation label is for==
  *
  * It is a '''template''', not a label on anything. A repository in the organisation can adopt one, at which point the
  * repository has a label with its own [[com.worxbend.codeberg4s.issues.LabelId]]. Deleting an organisation label
  * therefore does not touch the copies repositories already took, and reading a label off an issue never yields one of
  * these. A name repeats freely between the two levels — `golden/organization/org-labels-list.json` and
  * `golden/issue/labels-repo.json` both contain a `bug`, with different ids — which is why every route here addresses a
  * label by id.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than on each method.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with `404` when the organisation does not exist '''or''' is not
  *     visible to the configured credentials, and `404` again for a label that is not one of this organisation's. `401`
  *     without a token and `403` when the token lacks the scope. `422` '''and''' `400` both mean the request was
  *     rejected as invalid, per `docs/HAZARDS.md` §4.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, at its position for a listing — `$[2].id` rather than `$`.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here.
  *
  * ==Retries==
  *
  * Reads are [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]] and [[create]] is never retried, as every
  * `POST` in this library is. The two writes split, and the split is the same one
  * [[com.worxbend.codeberg4s.issues.IssueLabelApi]] made for the identical endpoints one level down: [[delete]] is
  * retried because it names one label by a row id Forgejo never reuses, and [[edit]] is not, because a repeat after a
  * lost success would overwrite whatever the label has become in the meantime.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class OrganizationLabelApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationLabelApi.Attempt = OrganizationLabelApi.Attempt(this)

  /** Lists an organisation's labels — `GET /orgs/{org}/labels`.
    *
    * '''Works anonymously on codeberg.org''': `golden/organization/org-labels-list.json` is that capture, and it
    * reported seventeen labels for `forgejo`.
    *
    * '''Paging ends where `rel="next"` says it does''', never where a short page suggests — Forgejo clamps `limit` to
    * its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past the end is `200` with `[]`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param query
    *   what to ask of the listing — today an optional ordering, absent to leave Forgejo's own, which the spec does not
    *   describe and so this library cannot name. `OrganizationLabelQuery.Empty` asks for everything
    * @param params
    *   the page to fetch and how many labels it may hold
    */
  def list(org: OrgName, query: OrganizationLabelQuery, params: PageParams): Future[Page[Label]] =
    pipeline.callPage(OrganizationLabelApi.listRequest(org, query, params), params)(using OrganizationDecoders.labels)

  /** Reads one label — `GET /orgs/{org}/labels/{id}`.
    *
    * Addressed by id rather than by name, because a name is unique neither within the organisation's set nor against a
    * repository's; see the class note.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param id
    *   the label's instance-wide identifier
    */
  def get(org: OrgName, id: LabelId): Future[Label] =
    pipeline.call(OrganizationLabelApi.getRequest(org, id), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.label)

  /** Creates a label on the organisation — `POST /orgs/{org}/labels`.
    *
    * '''Never retried.''' This library never repeats a `POST`: Forgejo offers no idempotency key, and a label name is
    * not unique, so a repeat after a lost success creates a '''second''' label with the same name and colour — which is
    * legal, invisible in a picker, and tedious to unpick. [[list]] resolves the uncertainty a transport failure leaves.
    *
    * '''Answers `201` with the created label''', including the id every other route here takes.
    *
    * '''Failures.''' The group contract above. A `422` is what Forgejo answers for a name or colour it rejects; the
    * colour is sent in the `#rrggbb` form the spec documents for input, so a `422` on colour is unlikely.
    *
    * @param org
    *   the organisation handle
    * @param command
    *   the label to create
    */
  def create(org: OrgName, command: CreateLabel): Future[Label] =
    pipeline.call(OrganizationLabelApi.createRequest(org, command), RetryEligibility.Never)(using
      OrganizationDecoders.label)

  /** Edits a label — `PATCH /orgs/{org}/labels/{id}`.
    *
    * '''Never retried''', and the contrast with [[delete]] is the point. This call names one label by an id the
    * instance never reuses and sets stated values, which looks idempotent — but a repeat after a lost success
    * overwrites whatever the label has become in the meantime, including somebody else's rename or recolour, and
    * Forgejo offers no conditional-update header that would let the instance refuse a stale write.
    * [[com.worxbend.codeberg4s.issues.IssueLabelApi.edit]] makes exactly this call for the repository-level endpoint.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. See
    * [[com.worxbend.codeberg4s.issues.EditLabel]] for why the two flags are `Option[Boolean]`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param id
    *   the label's instance-wide identifier
    * @param command
    *   what to change; an empty command is a well-formed request that changes nothing
    */
  def edit(org: OrgName, id: LabelId, command: EditLabel): Future[Label] =
    pipeline.call(OrganizationLabelApi.editRequest(org, id, command), RetryEligibility.Never)(using
      OrganizationDecoders.label)

  /** Deletes a label from the organisation's set — `DELETE /orgs/{org}/labels/{id}`.
    *
    * '''This does not touch the repositories that adopted it''', for the reason the class note gives: a repository's
    * copy is a different label with a different id.
    *
    * '''Retried''', because the request names exactly one object by an identifier the instance never reuses — a label
    * id is a database row id — so the state after N attempts is the state after one and nothing is created. The `{org}`
    * segment does not weaken that even though [[OrgName]] is a handle [[OrganizationApi.rename]] can move: the id
    * belongs to one organisation, so a stale handle can only produce a `404`. The cost a caller has to know is the
    * usual one — if the first attempt succeeded and its response was lost, the retry answers `404`, which then means
    * "it is gone" rather than "it was never there".
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param id
    *   the label's instance-wide identifier
    */
  def delete(org: OrgName, id: LabelId): Future[Unit] =
    pipeline.callUnit(OrganizationLabelApi.deleteRequest(org, id), RetryEligibility.AlwaysRetry)

/** The requests [[OrganizationLabelApi]] issues, its operation ids, and its typed rail. */
object OrganizationLabelApi:

  /** The stable operation id of [[OrganizationLabelApi.list]]. Safe to alert on. */
  val ListOperation: String = "orgs.labels.list"

  /** The stable operation id of the single-label read on [[OrganizationLabelApi]]. */
  val GetOperation: String = "orgs.labels.get"

  /** The stable operation id of [[OrganizationLabelApi.create]]. */
  val CreateOperation: String = "orgs.labels.create"

  /** The stable operation id of [[OrganizationLabelApi.edit]]. */
  val EditOperation: String = "orgs.labels.edit"

  /** The stable operation id of [[OrganizationLabelApi.delete]]. */
  val DeleteOperation: String = "orgs.labels.delete"

  /** The path segment the organisation label collection sits under. */
  private val LabelsSegment: String = "labels"

  /** The typed rail of [[OrganizationLabelApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.organizations.labels.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationLabelApi)(using exec: Exec[Future]):

    /** [[OrganizationLabelApi.list]] with its failure as a value. */
    def list(
        org: OrgName,
        query: OrganizationLabelQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Label]]] =
      exec.attempt(rail.list(org, query, params))

    /** The single-label read on [[OrganizationLabelApi]], with its failure as a value. */
    def get(org: OrgName, id: LabelId): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.get(org, id))

    /** [[OrganizationLabelApi.create]] with its failure as a value. */
    def create(org: OrgName, command: CreateLabel): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.create(org, command))

    /** [[OrganizationLabelApi.edit]] with its failure as a value. */
    def edit(org: OrgName, id: LabelId, command: EditLabel): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.edit(org, id, command))

    /** [[OrganizationLabelApi.delete]] with its failure as a value. */
    def delete(org: OrgName, id: LabelId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(org, id))

  private def listRequest(org: OrgName, query: OrganizationLabelQuery, params: PageParams): CodebergRequest =
    read(ListOperation, labelsPath(org), OrganizationQueries.labels(query, params))

  private def getRequest(org: OrgName, id: LabelId): CodebergRequest =
    read(GetOperation, labelPath(org, id), Nil)

  private def createRequest(org: OrgName, command: CreateLabel): CodebergRequest =
    write(
      CreateOperation,
      HttpMethod.Post,
      labelsPath(org),
      CreateLabelOptionDto.render(command),
    )

  private def editRequest(org: OrgName, id: LabelId, command: EditLabel): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      labelPath(org, id),
      EditLabelOptionDto.render(command),
    )

  private def deleteRequest(org: OrgName, id: LabelId): CodebergRequest =
    bodiless(DeleteOperation, HttpMethod.Delete, labelPath(org, id))

  private def labelsPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ LabelsSegment

  private def labelPath(org: OrgName, id: LabelId): List[String] =
    labelsPath(org) :+ id.value.toString
