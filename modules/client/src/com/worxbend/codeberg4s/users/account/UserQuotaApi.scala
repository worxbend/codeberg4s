package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.users.account.wire.AccountQueries

import scala.concurrent.Future

/** What the authenticated account is allowed to store, and what is taking up the room — `/user/quota`.
  *
  * Reached as `client.users.account.quota`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserQuotaApi.attempt]]
  * never fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Every operation here is a read, so the whole class is retried==
  *
  * All five are `GET`s and every one of them uses [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
  * There is no write in this group: quota is set by an instance administrator through a different API, and nothing an
  * account can do through these paths changes what it is allowed to store.
  *
  * ==Quota may not be turned on at all==
  *
  * Quota enforcement is a deployment setting. On an instance that does not use it, [[info]] answers a report with no
  * groups and no rules rather than a failure, and the three usage listings answer whatever the instance measured. An
  * empty [[com.worxbend.codeberg4s.users.account.QuotaInfo.groups]] is therefore '''not''' the same as "unlimited" —
  * nothing in the response says which it is, and this library does not guess.
  *
  * ==Evidence==
  *
  * '''Every model here is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest behind
  * `modules/codec/test/resources/golden` was anonymous and every route in this group requires a token, so no fixture
  * exists for any of them. The field sets and the nesting are the spec read literally; the nullability treatment is the
  * conservative one `docs/HAZARDS.md` §1 mandates. Where a shape is asserted in a test, the payload was written by hand
  * to match that definition — it is not evidence that Forgejo sends exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures:
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
  *     was rejected — every path in this group is `/user/…` and has no anonymous reading — and `403` when the token
  *     lacks the scope. [[check]] adds `422` for a subject the instance does not recognise.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model. Nothing
  *     in the quota models is required, so in practice this only arises for a body that is not JSON of the expected
  *     kind at all — an object where an array was declared, or a [[check]] answering something other than a boolean.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here: the one free-text
  * argument is a [[com.worxbend.codeberg4s.users.account.QuotaSubject]], validated by its own smart constructor.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class UserQuotaApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserQuotaApi.Attempt = UserQuotaApi.Attempt(this)

  /** Reads the account's quota report — `GET /user/quota`.
    *
    * '''The nested `used` tree is flattened''' into one [[com.worxbend.codeberg4s.users.account.QuotaUsedSize]]; see
    * that type and [[com.worxbend.codeberg4s.users.account.wire.QuotaInfoDto]] for the argument. A heading the instance
    * did not report is `None` and contributes nothing to
    * [[com.worxbend.codeberg4s.users.account.QuotaUsedSize.reportedTotal]], which is therefore a lower bound and not an
    * authoritative total.
    *
    * '''Failures.''' The group contract above.
    */
  def info(): Future[QuotaInfo] =
    pipeline.call(UserQuotaApi.infoRequest, RetryEligibility.IdempotentOnly)(using UserAccountDecoders.quota)

  /** Asks whether one more action of a given kind would fit — `GET /user/quota/check`.
    *
    * '''The whole body is a JSON boolean''', which is unique in this library: `true` means the instance would accept
    * the action, `false` that it would refuse it for quota reasons. There is no model to decode and nothing else in the
    * response.
    *
    * '''It is a question, not a reservation.''' Nothing holds the room between this call and the one it was asked
    * about, so a `true` can be followed by a `413` on the very next request. Treat it as a way to warn a user early,
    * never as a permission.
    *
    * '''Failures.''' The group contract above. A `422` is what a subject the instance does not recognise produces — the
    * vocabulary is not published, which is why [[com.worxbend.codeberg4s.users.account.QuotaSubject]] does not
    * enumerate it.
    *
    * @param subject
    *   what to ask about, in Forgejo's own dotted vocabulary such as `size:repos:public`
    */
  def check(subject: QuotaSubject): Future[Boolean] =
    pipeline.call(UserQuotaApi.checkRequest(subject), RetryEligibility.IdempotentOnly)(using
      UserAccountDecoders.quotaVerdict)

  /** Lists the Actions artifacts counting towards the account's quota — `GET /user/quota/artifacts`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). The spec
    * declares no `X-Total-Count` for this response, so [[com.worxbend.codeberg4s.paging.Page.totalCount]] is ordinarily
    * absent; absent means unknown, never zero.
    *
    * '''These are not addressable artifacts.''' The elements carry no id — see
    * [[com.worxbend.codeberg4s.users.account.QuotaUsedArtifact]] for why they are a different type from
    * [[com.worxbend.codeberg4s.repositories.actions.ActionArtifact]].
    *
    * '''Failures.''' The group contract above.
    */
  def artifacts(params: PageParams): Future[Page[QuotaUsedArtifact]] =
    pipeline.callPage(UserQuotaApi.artifactsRequest(params), params)(using UserAccountDecoders.quotaArtifacts)

  /** Lists the attachments counting towards the account's quota — `GET /user/quota/attachments`.
    *
    * '''Paging.''' As [[artifacts]].
    *
    * '''Failures.''' The group contract above.
    */
  def attachments(params: PageParams): Future[Page[QuotaUsedAttachment]] =
    pipeline.callPage(UserQuotaApi.attachmentsRequest(params), params)(using UserAccountDecoders.quotaAttachments)

  /** Lists the package versions counting towards the account's quota — `GET /user/quota/packages`.
    *
    * '''One element per version''', not per package: a package with ten versions occupies ten entries, which is what
    * makes the listing useful for finding what to delete.
    *
    * '''Paging.''' As [[artifacts]].
    *
    * '''Failures.''' The group contract above.
    */
  def packages(params: PageParams): Future[Page[QuotaUsedPackage]] =
    pipeline.callPage(UserQuotaApi.packagesRequest(params), params)(using UserAccountDecoders.quotaPackages)

/** The requests this group issues, its operation ids, and its typed rail. */
object UserQuotaApi:

  /** The stable operation id of [[UserQuotaApi.info]]. Safe to alert on. */
  val InfoOperation: String = "users.account.quota.info"

  /** The stable operation id of [[UserQuotaApi.check]]. */
  val CheckOperation: String = "users.account.quota.check"

  /** The stable operation id of [[UserQuotaApi.artifacts]]. */
  val ArtifactsOperation: String = "users.account.quota.artifacts"

  /** The stable operation id of [[UserQuotaApi.attachments]]. */
  val AttachmentsOperation: String = "users.account.quota.attachments"

  /** The stable operation id of [[UserQuotaApi.packages]]. */
  val PackagesOperation: String = "users.account.quota.packages"

  /** The typed rail of [[UserQuotaApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.account.quota.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: UserQuotaApi)(using exec: Exec[Future]):

    /** [[UserQuotaApi.info]] with its failure as a value. */
    def info(): Future[Either[CodebergError, QuotaInfo]] =
      exec.attempt(rail.info())

    /** [[UserQuotaApi.check]] with its failure as a value. */
    def check(subject: QuotaSubject): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.check(subject))

    /** [[UserQuotaApi.artifacts]] with its failure as a value. */
    def artifacts(params: PageParams): Future[Either[CodebergError, Page[QuotaUsedArtifact]]] =
      exec.attempt(rail.artifacts(params))

    /** [[UserQuotaApi.attachments]] with its failure as a value. */
    def attachments(params: PageParams): Future[Either[CodebergError, Page[QuotaUsedAttachment]]] =
      exec.attempt(rail.attachments(params))

    /** [[UserQuotaApi.packages]] with its failure as a value. */
    def packages(params: PageParams): Future[Either[CodebergError, Page[QuotaUsedPackage]]] =
      exec.attempt(rail.packages(params))

  private def infoRequest: CodebergRequest =
    read(InfoOperation, quotaPath, Nil)

  private def checkRequest(subject: QuotaSubject): CodebergRequest =
    read(CheckOperation, quotaPath :+ "check", AccountQueries.quotaCheck(subject))

  private def artifactsRequest(params: PageParams): CodebergRequest =
    read(ArtifactsOperation, quotaPath :+ "artifacts", AccountQueries.paging(params))

  private def attachmentsRequest(params: PageParams): CodebergRequest =
    read(AttachmentsOperation, quotaPath :+ "attachments", AccountQueries.paging(params))

  private def packagesRequest(params: PageParams): CodebergRequest =
    read(PackagesOperation, quotaPath :+ "packages", AccountQueries.paging(params))

  private def quotaPath: List[String] =
    AccountRequests.path("quota")
