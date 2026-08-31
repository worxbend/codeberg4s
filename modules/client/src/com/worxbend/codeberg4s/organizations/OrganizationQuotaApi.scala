package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.organizations.wire.OrganizationQueries
import com.worxbend.codeberg4s.paging.{Page, PageParams}

import scala.concurrent.Future

/** An organisation's storage quota — the limits that apply to it, what it has used, and what is using it.
  *
  * Reached as `client.organizations.quota`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on
  * [[OrganizationQuotaApi.attempt]] never fail and return an `Either` instead. The typed rail is derived from this one
  * by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Every operation here is a `GET`==
  *
  * Quota is configured by a site administrator through a different part of the API; nothing in this class changes
  * anything. So all five are [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]] and there is no retry
  * judgement to make.
  *
  * ==Quota is optional, and a `404` is the ordinary answer on an instance without it==
  *
  * Forgejo ships quota disabled and many deployments leave it that way; codeberg.org did when
  * `modules/codec/test/resources/golden` was harvested. An instance that does not track quota answers these routes
  * `404`, which is indistinguishable from "no such organisation" — and a caller who is not entitled to see the figures
  * gets `403`. Neither is exceptional. `docs/HAZARDS.md` §2 explains why the "optional" in `docs/API_INVENTORY.md`
  * cannot be trusted for any of this: the pinned spec declares `security` once, globally, with zero per-operation
  * overrides, so it carries no per-endpoint authentication information at all.
  *
  * ==Everything here is derived from the spec==
  *
  * '''No quota fixture exists anywhere in this repository.''' The models in
  * [[com.worxbend.codeberg4s.organizations.QuotaInfo]] and [[com.worxbend.codeberg4s.organizations.QuotaArtifact]] are
  * `spec/swagger.v1.json` read literally under the rule `docs/HAZARDS.md` §1 forces on the whole API, and the payloads
  * asserted in the suites were written by hand to match those definitions. They are not evidence that Forgejo sends
  * exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than on each method.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with `404` when the organisation does not exist, is not visible,
  *     '''or''' the instance does not implement quota; `403` when the caller may not see the figures; `401` without a
  *     token. `422` '''and''' `400` both mean the request was rejected as invalid — for [[check]], a `422` is what an
  *     unknown [[QuotaSubject]] produces.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here: every argument is
  * an already-validated type.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class OrganizationQuotaApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationQuotaApi.Attempt = OrganizationQuotaApi.Attempt(this)

  /** Reads the organisation's quota and usage — `GET /orgs/{org}/quota`.
    *
    * '''Rule names are administrators-only.''' The spec marks `QuotaRuleInfo.name` "only shown to admins", so a caller
    * without that standing sees the limits and the subjects with [[QuotaRule.name]] absent. That is not a decoding
    * problem and not a failure; see [[com.worxbend.codeberg4s.organizations.QuotaRule]].
    *
    * '''Absent is not zero.''' Every size in the result is an `Option`, because an instance that does not track a
    * category sends nothing for it and reporting `0` would let a caller draw a usage chart out of silence. See
    * [[com.worxbend.codeberg4s.organizations.QuotaInfo]].
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    */
  def get(org: OrgName): Future[QuotaInfo] =
    pipeline.call(OrganizationQuotaApi.getRequest(org), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.quotaInfo)

  /** Asks whether the organisation is still within quota for one subject — `GET /orgs/{org}/quota/check`.
    *
    * '''The body is a bare JSON boolean''', not an object: the spec types the `200` as `{"type": "boolean"}`, and
    * `true` means the action the subject describes would be accepted. This is the only endpoint in the group whose
    * response has no model at all.
    *
    * '''A `false` is an answer, not a failure.''' It arrives on the success channel like any other `200`. What travels
    * on the error channel is the instance refusing the question — `422` for a subject it does not recognise, `403` for
    * a caller who may not ask, `404` for an instance without quota at all.
    *
    * '''The subject vocabulary is Forgejo's and the spec does not state it'''; see [[QuotaSubject]] for why this
    * library will not enumerate it, and [[get]] for how to obtain subjects an instance actually applies.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param subject
    *   what to ask about, such as the value of one [[com.worxbend.codeberg4s.organizations.QuotaRule.subjects]] entry
    */
  def check(org: OrgName, subject: QuotaSubject): Future[Boolean] =
    pipeline.call(OrganizationQuotaApi.checkRequest(org, subject), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.quotaCheck)

  /** Lists the Actions artifacts counting towards the quota — `GET /orgs/{org}/quota/artifacts`.
    *
    * '''A report, not a set of handles.''' The entries carry no identifier and nothing here can delete one; see
    * [[com.worxbend.codeberg4s.organizations.QuotaArtifact]].
    *
    * '''Paging ends where `rel="next"` says it does''', never where a short page suggests (`docs/HAZARDS.md` §5).
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def artifacts(org: OrgName, params: PageParams): Future[Page[QuotaArtifact]] =
    pipeline.callPage(OrganizationQuotaApi.artifactsRequest(org, params), params)(using
      OrganizationDecoders.quotaArtifacts)

  /** Lists the attachments counting towards the quota — `GET /orgs/{org}/quota/attachments`.
    *
    * Each entry says what it hangs off, through [[com.worxbend.codeberg4s.organizations.QuotaAttachment.containedIn]] —
    * an issue, a comment or a release. Both links there point at the container, never at the attachment.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def attachments(org: OrgName, params: PageParams): Future[Page[QuotaAttachment]] =
    pipeline.callPage(OrganizationQuotaApi.attachmentsRequest(org, params), params)(using
      OrganizationDecoders.quotaAttachments)

  /** Lists the package versions counting towards the quota — `GET /orgs/{org}/quota/packages`.
    *
    * '''One entry per version''', not per package: a package with ten versions produces ten entries, each with its own
    * size.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def packages(org: OrgName, params: PageParams): Future[Page[QuotaPackage]] =
    pipeline.callPage(OrganizationQuotaApi.packagesRequest(org, params), params)(using
      OrganizationDecoders.quotaPackages)

/** The requests [[OrganizationQuotaApi]] issues, its operation ids, and its typed rail. */
object OrganizationQuotaApi:

  /** The stable operation id of [[OrganizationQuotaApi.get]]. Safe to alert on. */
  val GetOperation: String = "orgs.quota.get"

  /** The stable operation id of [[OrganizationQuotaApi.check]]. */
  val CheckOperation: String = "orgs.quota.check"

  /** The stable operation id of [[OrganizationQuotaApi.artifacts]]. */
  val ArtifactsOperation: String = "orgs.quota.artifacts.list"

  /** The stable operation id of [[OrganizationQuotaApi.attachments]]. */
  val AttachmentsOperation: String = "orgs.quota.attachments.list"

  /** The stable operation id of [[OrganizationQuotaApi.packages]]. */
  val PackagesOperation: String = "orgs.quota.packages.list"

  /** The path segment every route in this class sits under. */
  private val QuotaSegment: String = "quota"

  /** The typed rail of [[OrganizationQuotaApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.organizations.quota.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationQuotaApi)(using exec: Exec[Future]):

    /** [[OrganizationQuotaApi.get]] with its failure as a value. */
    def get(org: OrgName): Future[Either[CodebergError, QuotaInfo]] =
      exec.attempt(rail.get(org))

    /** [[OrganizationQuotaApi.check]] with its failure as a value. A `false` verdict is still a `Right`. */
    def check(org: OrgName, subject: QuotaSubject): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.check(org, subject))

    /** [[OrganizationQuotaApi.artifacts]] with its failure as a value. */
    def artifacts(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[QuotaArtifact]]] =
      exec.attempt(rail.artifacts(org, params))

    /** [[OrganizationQuotaApi.attachments]] with its failure as a value. */
    def attachments(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[QuotaAttachment]]] =
      exec.attempt(rail.attachments(org, params))

    /** [[OrganizationQuotaApi.packages]] with its failure as a value. */
    def packages(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[QuotaPackage]]] =
      exec.attempt(rail.packages(org, params))

  private def getRequest(org: OrgName): CodebergRequest =
    read(GetOperation, quotaPath(org), Nil)

  private def checkRequest(org: OrgName, subject: QuotaSubject): CodebergRequest =
    read(CheckOperation, quotaPath(org) :+ "check", OrganizationQueries.quotaCheck(subject))

  private def artifactsRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(ArtifactsOperation, quotaPath(org) :+ "artifacts", OrganizationQueries.paging(params))

  private def attachmentsRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(AttachmentsOperation, quotaPath(org) :+ "attachments", OrganizationQueries.paging(params))

  private def packagesRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(PackagesOperation, quotaPath(org) :+ "packages", OrganizationQueries.paging(params))

  private def quotaPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ QuotaSegment
