package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.account.wire.AccountOptionDto
import com.worxbend.codeberg4s.users.account.wire.AccountQueries

import scala.concurrent.Future

/** The OAuth2 applications the authenticated account has registered — `/user/applications/oauth2`.
  *
  * Reached as `client.users.account.applications`. Both error rails are here (ADR-0005): the methods on this class fail
  * the `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on
  * [[UserApplicationApi.attempt]] never fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==One response in this whole library carries a credential you cannot ask for again==
  *
  * [[create]] answers an [[OAuth2Application]] whose
  * [[com.worxbend.codeberg4s.users.account.OAuth2Application.clientSecret]] is present. Nothing else here ever
  * populates it: Forgejo stores the secret hashed, so [[get]] and [[list]] return the same application with no secret
  * at all. '''A caller who does not persist the value returned by [[create]] cannot recover it.''' The only ways
  * forward are to register another application, or to accept whatever [[update]] issues — which is precisely why
  * [[update]] is never retried.
  *
  * The value is a [[ClientSecret]], which masks itself in `toString`, in interpolation and in the generated `toString`
  * of the application holding it, so it cannot leak into a log or into a [[com.worxbend.codeberg4s.CodebergError]] on
  * the way past. Revealing it is an explicit act; see that type.
  *
  * The mask only covers a secret this library managed to decode. The other way out is the raw body: a `2xx` that does
  * not match the model puts an excerpt of the payload into
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed.snippet]], and on these two responses that payload is the
  * secret. So [[create]] and [[update]] read their response through a decoder marked
  * [[com.worxbend.codeberg4s.core.Decode.sensitive]], which makes the pipeline report
  * [[com.worxbend.codeberg4s.core.ApiPipeline.redactedSnippet]] instead. [[get]] and [[list]] keep the excerpt, because
  * the bodies they read carry no secret to lose.
  *
  * ==Evidence==
  *
  * '''Every model here is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest behind
  * `modules/codec/test/resources/golden` was anonymous and every route in this group requires a token, so no fixture
  * exists for any of them. The field sets are the spec read literally; the nullability treatment is the conservative
  * one `docs/HAZARDS.md` §1 mandates for the whole API. Where a shape is asserted in a test, the payload was written by
  * hand to match that definition — it is not evidence that Forgejo sends exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures:
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
  *     was rejected — every path in this group is `/user/…` and has no anonymous reading — `403` when the token lacks
  *     the scope, and `404` when no application of the account has that id. `400` is what the creation answers for a
  *     payload it rejects; `docs/HAZARDS.md` §4 records Forgejo using `400` where a reader would expect `422`.
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
final class UserApplicationApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserApplicationApi.Attempt = UserApplicationApi.Attempt(this)

  /** Lists the account's OAuth2 applications — `GET /user/applications/oauth2`.
    *
    * '''No secrets here.''' Every element has an absent
    * [[com.worxbend.codeberg4s.users.account.OAuth2Application.clientSecret]]; see the class note.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). This is one of
    * the operations whose spec response declares an `X-Total-Count` header, so
    * [[com.worxbend.codeberg4s.paging.Page.totalCount]] is usually present; it is still an `Option`, because a header
    * is a promise the instance makes and not one this library can keep on its behalf.
    *
    * '''Failures.''' The group contract above.
    */
  def list(params: PageParams): Future[Page[OAuth2Application]] =
    pipeline.callPage(UserApplicationApi.listRequest(params), params)(using UserAccountDecoders.applications)

  /** Reads one of the account's applications — `GET /user/applications/oauth2/{id}`.
    *
    * '''This is not how the client secret is recovered.''' The response carries no secret, whatever the application
    * looked like when it was created; see the class note.
    *
    * '''Failures.''' The group contract above.
    */
  def get(id: OAuth2ApplicationId): Future[OAuth2Application] =
    pipeline.call(UserApplicationApi.getRequest(id), RetryEligibility.IdempotentOnly)(using
      UserAccountDecoders.application)

  /** Registers a new OAuth2 application — `POST /user/applications/oauth2`.
    *
    * '''Never retried.''' Application names are not unique and Forgejo offers no idempotency key, so a repeat registers
    * a second application with a second client id and a second secret. Worse than the duplicate is the silence: the
    * caller would hold the second secret while an application they cannot see holds the first. A transport failure
    * therefore leaves the caller unsure whether an application exists, which [[list]] resolves.
    *
    * '''The result carries the only copy of the client secret.''' Persist
    * [[com.worxbend.codeberg4s.users.account.OAuth2Application.clientSecret]] now — see the class note and
    * [[ClientSecret]].
    *
    * '''Answers `201`.'''
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the payload, most often a blank name,
    * which [[com.worxbend.codeberg4s.users.account.OAuth2ApplicationDefinition.named]] has already refused.
    */
  def create(definition: OAuth2ApplicationDefinition): Future[OAuth2Application] =
    pipeline.call(UserApplicationApi.createRequest(definition), RetryEligibility.Never)(using
      UserAccountDecoders.issuedApplication)

  /** Replaces an application's definition — `PATCH /user/applications/oauth2/{id}`.
    *
    * '''A replacement, not a patch.''' `spec/swagger.v1.json` gives this endpoint the '''create''' body model, which
    * has no notion of an unmentioned key: whatever the definition does not state is stored as its zero value. That is
    * why one command type serves both calls — see [[com.worxbend.codeberg4s.users.account.OAuth2ApplicationDefinition]]
    * — and why a caller who means to change one field must restate the others.
    *
    * '''Never retried, and the reason is a credential rather than a duplicate.''' The response model carries a
    * `client_secret`, and the spec does not say whether an update re-issues one. If it does, then a first attempt that
    * succeeded and whose response was lost has already minted a secret that a retry would replace — invalidating a
    * credential some deployment may already be authenticating with, and doing it silently. This library will not take
    * that risk on a caller's behalf; a caller who has established what their instance does can re-issue the call
    * themselves.
    *
    * '''Answers `200`''' with the application as it now stands.
    *
    * '''Failures.''' The group contract above.
    */
  def update(id: OAuth2ApplicationId, definition: OAuth2ApplicationDefinition): Future[OAuth2Application] =
    pipeline.call(UserApplicationApi.updateRequest(id, definition), RetryEligibility.Never)(using
      UserAccountDecoders.issuedApplication)

  /** Deletes one of the account's applications — `DELETE /user/applications/oauth2/{id}`.
    *
    * '''Retried''', because the request names one instance-wide identifier the server never reuses — an application id
    * is a database row id — so the end state after any number of attempts is the one attempt would have produced, and
    * nothing is created. The one cost: if the first attempt succeeded and its response was lost, the retry addresses
    * something that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone", not
    * necessarily "it was never there".
    *
    * '''Every token issued through the application stops working.''' That is Forgejo's behaviour and not something this
    * call can soften.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def delete(id: OAuth2ApplicationId): Future[Unit] =
    pipeline.callUnit(UserApplicationApi.deleteRequest(id), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object UserApplicationApi:

  /** The stable operation id of [[UserApplicationApi.list]]. Safe to alert on. */
  val ListOperation: String = "users.account.applications.list"

  /** The stable operation id of the single-application read on [[UserApplicationApi]]. */
  val GetOperation: String = "users.account.applications.get"

  /** The stable operation id of [[UserApplicationApi.create]]. */
  val CreateOperation: String = "users.account.applications.create"

  /** The stable operation id of [[UserApplicationApi.update]]. */
  val UpdateOperation: String = "users.account.applications.update"

  /** The stable operation id of [[UserApplicationApi.delete]]. */
  val DeleteOperation: String = "users.account.applications.delete"

  /** The typed rail of [[UserApplicationApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.users.account.applications.attempt`. Each method is the convenience-rail method with its
    * failure channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is
    * missing from both.
    */
  final class Attempt private[codeberg4s] (rail: UserApplicationApi)(using exec: Exec[Future]):

    /** [[UserApplicationApi.list]] with its failure as a value. */
    def list(params: PageParams): Future[Either[CodebergError, Page[OAuth2Application]]] =
      exec.attempt(rail.list(params))

    /** [[UserApplicationApi.get]] with its failure as a value. */
    def get(id: OAuth2ApplicationId): Future[Either[CodebergError, OAuth2Application]] =
      exec.attempt(rail.get(id))

    /** [[UserApplicationApi.create]] with its failure as a value. */
    def create(definition: OAuth2ApplicationDefinition): Future[Either[CodebergError, OAuth2Application]] =
      exec.attempt(rail.create(definition))

    /** [[UserApplicationApi.update]] with its failure as a value. */
    def update(
        id: OAuth2ApplicationId,
        definition: OAuth2ApplicationDefinition,
    ): Future[Either[CodebergError, OAuth2Application]] =
      exec.attempt(rail.update(id, definition))

    /** [[UserApplicationApi.delete]] with its failure as a value. */
    def delete(id: OAuth2ApplicationId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(id))

  private def listRequest(params: PageParams): CodebergRequest =
    AccountRequests.read(ListOperation, applicationsPath, AccountQueries.paging(params))

  private def getRequest(id: OAuth2ApplicationId): CodebergRequest =
    AccountRequests.read(GetOperation, applicationPath(id), Nil)

  private def createRequest(definition: OAuth2ApplicationDefinition): CodebergRequest =
    AccountRequests.write(
      CreateOperation,
      HttpMethod.Post,
      applicationsPath,
      AccountOptionDto.renderApplication(definition),
    )

  private def updateRequest(id: OAuth2ApplicationId, definition: OAuth2ApplicationDefinition): CodebergRequest =
    AccountRequests.write(
      UpdateOperation,
      HttpMethod.Patch,
      applicationPath(id),
      AccountOptionDto.renderApplication(definition),
    )

  private def deleteRequest(id: OAuth2ApplicationId): CodebergRequest =
    AccountRequests.remove(DeleteOperation, applicationPath(id))

  private def applicationsPath: List[String] =
    AccountRequests.path("applications", "oauth2")

  private def applicationPath(id: OAuth2ApplicationId): List[String] =
    applicationsPath :+ id.value.toString
