package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.CodebergRequest.remove
import com.worxbend.codeberg4s.core.CodebergRequest.removeWithBody
import com.worxbend.codeberg4s.core.CodebergRequest.write
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.users.account.wire.AccountOptionDto
import com.worxbend.codeberg4s.users.account.wire.AccountQueries

import scala.concurrent.Future

/** The authenticated account itself: its settings, its avatar, its email addresses, its repositories and its teams.
  *
  * Reached as `client.users.account`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserAccountApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Every path here is `/user/…`, and that is the whole distinction==
  *
  * `/user/…` means "whoever the configured credentials are" and needs a token. `/users/{username}/…` names an account
  * explicitly and is a different API with a different security posture, owned by
  * [[com.worxbend.codeberg4s.users.UserApi]]. Nothing in this class takes a username, because none of these endpoints
  * has one — the account is the credentials.
  *
  * The four sibling classes in this package cover the rest of the account's surface: [[UserActionApi]] for its Actions
  * configuration, [[UserApplicationApi]] for its OAuth2 applications, [[UserHookApi]] for its webhooks and
  * [[UserQuotaApi]] for its storage quota.
  *
  * ==Evidence==
  *
  * '''The models this class reads are derived from `spec/swagger.v1.json`, with one exception.''' The harvest behind
  * `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no fixture exists
  * for `UserSettings` or `Email`; the field sets are the spec read literally and the nullability treatment is the
  * conservative one `docs/HAZARDS.md` §1 mandates. The exception is
  * [[com.worxbend.codeberg4s.repositories.Repository]], returned by [[repositories]] and [[createRepository]], which
  * '''is''' backed by captured payloads — `golden/user/user-repos-list.json` and `golden/repository/repo-single.json` —
  * because the same model is public elsewhere.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
  *     was rejected — every path in this group is `/user/…` and has no anonymous reading — and `403` when the token
  *     lacks the scope. `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4
  *     records Forgejo using `400` where a reader would expect `422`.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every `POST` uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because Forgejo offers no idempotency key. The two
  * remaining writes — the settings `PATCH` and the two `DELETE`s — are retried, and each states its own argument: all
  * three name the authenticated account, which is an identity the instance never reassigns, and all three end in the
  * state they asked for however many times they run.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class UserAccountApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserAccountApi.Attempt = UserAccountApi.Attempt(this)

  // --- settings -------------------------------------------------------------

  /** Reads the account's profile and privacy settings — `GET /user/settings`.
    *
    * '''Not the same thing as `GET /user`.''' [[com.worxbend.codeberg4s.users.UserApi.current]] answers the public
    * `User` model; this answers what the account has '''configured''', including the three privacy flags that decide
    * what the public model shows to anyone else. A caller building a settings screen wants this one.
    *
    * '''Failures.''' The group contract above.
    */
  def settings(): Future[UserSettings] =
    pipeline.call(UserAccountApi.settingsRequest, RetryEligibility.IdempotentOnly)(using UserAccountDecoders.settings)

  /** Changes the account's settings — `PATCH /user/settings`.
    *
    * '''Retried''', because the request names one identity the instance never reassigns — the account the credentials
    * belong to — and states the value it wants each key it mentions to have. Applying that twice leaves the account
    * exactly where applying it once would, and creates nothing. Unlike a delete there is not even a `404` to explain
    * afterwards: a retry after a lost success simply reports the settings in the state that was asked for.
    *
    * '''What the command does not mention is left alone'''; see [[UpdateUserSettings]] for how clearing a field is
    * spelled differently from leaving it untouched.
    *
    * '''Answers `200`''' with the settings as they now stand, which is why this returns a value rather than `Unit`.
    *
    * '''Failures.''' The group contract above. A `422` is what a language tag or a theme name the instance does not
    * ship produces.
    */
  def updateSettings(command: UpdateUserSettings): Future[UserSettings] =
    pipeline.call(UserAccountApi.updateSettingsRequest(command), RetryEligibility.AlwaysRetry)(using
      UserAccountDecoders.settings)

  // --- avatar ---------------------------------------------------------------

  /** Replaces the account's avatar — `POST /user/avatar`.
    *
    * '''A base64 string in a JSON body, not a multipart upload.''' That is unusual — release assets and repository
    * avatars elsewhere in Forgejo are multipart — and it is what `spec/swagger.v1.json` declares: the body is an
    * `UpdateUserAvatarOption` whose one `image` property is documented as base64. See [[AvatarImage]], which is how a
    * caller gets from a file on disk to a value this method takes.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. The effect of a repeat would in
    * fact be harmless — the second upload overwrites the first with the same bytes — but Forgejo offers no idempotency
    * key, and this library does not decide on a caller's behalf that repeating a mutating `POST` is safe. A caller who
    * wants that can re-issue the call.
    *
    * '''Answers `204`''', so nothing comes back and there is no new avatar URL to read; the account's `avatar_url`
    * through [[com.worxbend.codeberg4s.users.UserApi.current]] is how the change is observed.
    *
    * '''Failures.''' The group contract above. A `4xx` is also what an image the instance considers too large or of an
    * unsupported type produces; both limits are deployment settings, so [[AvatarImage]] does not pre-empt them.
    */
  def updateAvatar(image: AvatarImage): Future[Unit] =
    pipeline.callUnit(UserAccountApi.updateAvatarRequest(image), RetryEligibility.Never)

  /** Removes the account's avatar — `DELETE /user/avatar`.
    *
    * '''Retried''', because the request names one identity the instance never reassigns and asks for a state rather
    * than an object: after any number of attempts the account has no custom avatar, and nothing is created. The one
    * cost a caller should know is narrower than for a delete-by-name — there is no id to go stale, so a retry after a
    * lost success answers `204` again rather than `404`; what it can do is remove an avatar uploaded between the two
    * attempts, which is a race a caller uploading and deleting concurrently already has.
    *
    * '''Answers `204`.''' Forgejo falls back to the account's generated identicon.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteAvatar(): Future[Unit] =
    pipeline.callUnit(UserAccountApi.deleteAvatarRequest, RetryEligibility.AlwaysRetry)

  // --- emails ---------------------------------------------------------------

  /** Lists the account's email addresses — `GET /user/emails`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so the whole list arrives at once and the result is a `Vector` rather than a
    * [[com.worxbend.codeberg4s.paging.Page]] — a page reporting a window nobody chose would be a lie about what was
    * requested. An account holds a handful of addresses, so this is not the unbounded read that would make paging
    * necessary.
    *
    * '''Failures.''' The group contract above. An address the instance sends that
    * [[com.worxbend.codeberg4s.users.account.EmailAddress.from]] cannot read fails the whole listing at `$[n].email`
    * rather than being dropped; see [[com.worxbend.codeberg4s.users.account.wire.EmailDto]] for why that direction is
    * the safe one.
    */
  def emails(): Future[Vector[Email]] =
    pipeline.call(UserAccountApi.emailsRequest, RetryEligibility.IdempotentOnly)(using UserAccountDecoders.emails)

  /** Adds email addresses to the account — `POST /user/emails`.
    *
    * '''One address plus a tail, so an empty request cannot be spelled.''' `CreateEmailOption` would happily carry an
    * empty array, and sending one asks the instance to do nothing; making that unrepresentable is cheaper than
    * documenting it.
    *
    * '''Never retried, and here the reason is not merely the `POST` rule.''' Adding an address is genuinely not
    * idempotent from the caller's side: Forgejo answers `422` when the address is already on an account, so a retry
    * after a lost success turns a request that worked into a failure that reads like a mistake. Compare
    * [[deleteEmails]], which is idempotent by address and is retried.
    *
    * '''Answers `201` with the account's addresses''' — the spec declares the response as an `EmailList`, so this
    * returns what the account now holds rather than `Unit`. Whether that list is the whole set or only the additions is
    * not stated by the spec, so nothing here promises either.
    *
    * '''Unverified until confirmed.''' A newly added address arrives with
    * [[com.worxbend.codeberg4s.users.account.Email.isVerified]] `false` on an instance that requires confirmation, and
    * cannot be made primary until it is.
    *
    * '''Failures.''' The group contract above. A `422` is what an address already in use — on this account or another —
    * produces.
    *
    * @param first
    *   the address to add
    * @param rest
    *   any further addresses, added in the same request
    */
  def addEmails(first: EmailAddress, rest: EmailAddress*): Future[Vector[Email]] =
    pipeline.call(UserAccountApi.addEmailsRequest(first +: rest.toVector), RetryEligibility.Never)(using
      UserAccountDecoders.emails)

  /** Removes email addresses from the account — `DELETE /user/emails`.
    *
    * '''This `DELETE` carries a body''', which is unusual and is the only one in the library: the addresses to remove
    * are named in a `DeleteEmailOption` and nowhere else, so there is no other way to issue the call. See
    * [[removeWithBody]].
    *
    * '''Retried''', because the request is idempotent by address: it names the exact values to remove, and after any
    * number of attempts those addresses are not on the account. Nothing is created, and — unlike a delete addressed by
    * a reusable name in a shared namespace — the only actor who can re-add one of these between attempts is the account
    * holder themselves, through [[addEmails]], which they would have to be doing concurrently. A retry after a lost
    * success answers `204` again rather than `404`.
    *
    * '''One address plus a tail''', for the reason [[addEmails]] gives.
    *
    * '''Answers `204`.''' Nothing comes back, so [[emails]] is how the result is observed.
    *
    * '''Failures.''' The group contract above. A `404` is what an address the account does not hold produces, and
    * Forgejo refuses to remove the primary address — which arrives as a `4xx` rather than as a partial success.
    *
    * @param first
    *   the address to remove
    * @param rest
    *   any further addresses, removed in the same request
    */
  def deleteEmails(first: EmailAddress, rest: EmailAddress*): Future[Unit] =
    pipeline.callUnit(UserAccountApi.deleteEmailsRequest(first +: rest.toVector), RetryEligibility.AlwaysRetry)

  // --- repositories ---------------------------------------------------------

  /** Lists the repositories the account owns or can see — `GET /user/repos`.
    *
    * '''Wider than `GET /users/{username}/repos`.''' That listing shows what the configured credentials may see of
    * another account; this one is the account's own view, so it includes its private repositories and the ones it
    * reaches through a team. [[com.worxbend.codeberg4s.users.UserApi.repositories]] is the other call.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above. A `422` is what an ordering the instance does not know produces, which
    * [[RepositoryOrder]] exists to prevent.
    *
    * @param order
    *   how to sort the result; [[RepositoryOrder.Default]] sends no `order_by` and takes the instance's own ordering
    */
  def repositories(order: RepositoryOrder, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(UserAccountApi.repositoriesRequest(order, params), params)(using UserAccountDecoders.repositories)

  /** Creates a repository owned by the account — `POST /user/repos`.
    *
    * '''No owner in the path, and that is the point.''' The repository belongs to whoever the credentials are; the
    * admin and organisation creation routes are how a repository is made for someone else, and neither is in this
    * group.
    *
    * '''Never retried.''' Forgejo offers no idempotency key. The practical effect of a repeat is a `409` rather than a
    * duplicate — the name is unique per owner — but a `409` after a lost success is indistinguishable from a `409`
    * because the caller already had that name, so a retry would turn "it worked" into an error nobody can classify.
    *
    * '''Answers `201`''' with the repository as created.
    *
    * '''Failures.''' The group contract above, plus two this endpoint declares that no other creation route in the
    * library does: `409` when the account already owns a repository of that name, and `413` when the account is over
    * its storage quota — see [[UserQuotaApi]].
    */
  def createRepository(command: CreateRepository): Future[Repository] =
    pipeline.call(UserAccountApi.createRepositoryRequest(command), RetryEligibility.Never)(using
      UserAccountDecoders.repository)

  // --- teams ----------------------------------------------------------------

  /** Lists the teams the account belongs to — `GET /user/teams`.
    *
    * '''Across every organisation''', not within one: the elements carry their own
    * [[com.worxbend.codeberg4s.organizations.Team.organization]], which is how a caller tells them apart. That field is
    * an `Option` on the shared model because an organisation-scoped listing omits it; on this listing it is the only
    * thing that says which organisation a team is in, so a payload without it leaves a team a caller cannot place.
    *
    * '''Paging.''' As [[repositories]]. This response declares an `X-Total-Count` header in the spec, so
    * [[com.worxbend.codeberg4s.paging.Page.totalCount]] is usually present — still an `Option`, because a header is a
    * promise the instance makes and not one this library can keep on its behalf.
    *
    * '''Failures.''' The group contract above. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means an
    * element carried no `id` or `name`, reported at `$[n]`.
    */
  def teams(params: PageParams): Future[Page[Team]] =
    pipeline.callPage(UserAccountApi.teamsRequest(params), params)(using UserAccountDecoders.teams)

/** The requests this group issues, its operation ids, and its typed rail. */
object UserAccountApi:

  /** The stable operation id of [[UserAccountApi.settings]]. Safe to alert on. */
  val SettingsOperation: String = "users.account.settings.get"

  /** The stable operation id of [[UserAccountApi.updateSettings]]. */
  val UpdateSettingsOperation: String = "users.account.settings.update"

  /** The stable operation id of [[UserAccountApi.updateAvatar]]. */
  val UpdateAvatarOperation: String = "users.account.avatar.update"

  /** The stable operation id of [[UserAccountApi.deleteAvatar]]. */
  val DeleteAvatarOperation: String = "users.account.avatar.delete"

  /** The stable operation id of [[UserAccountApi.emails]]. */
  val EmailsOperation: String = "users.account.emails.list"

  /** The stable operation id of [[UserAccountApi.addEmails]]. */
  val AddEmailsOperation: String = "users.account.emails.add"

  /** The stable operation id of [[UserAccountApi.deleteEmails]]. */
  val DeleteEmailsOperation: String = "users.account.emails.delete"

  /** The stable operation id of [[UserAccountApi.repositories]]. */
  val RepositoriesOperation: String = "users.account.repos.list"

  /** The stable operation id of [[UserAccountApi.createRepository]]. */
  val CreateRepositoryOperation: String = "users.account.repos.create"

  /** The stable operation id of [[UserAccountApi.teams]]. */
  val TeamsOperation: String = "users.account.teams.list"

  /** The typed rail of [[UserAccountApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.account.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: UserAccountApi)(using exec: Exec[Future]):

    /** [[UserAccountApi.settings]] with its failure as a value. */
    def settings(): Future[Either[CodebergError, UserSettings]] =
      exec.attempt(rail.settings())

    /** [[UserAccountApi.updateSettings]] with its failure as a value. */
    def updateSettings(command: UpdateUserSettings): Future[Either[CodebergError, UserSettings]] =
      exec.attempt(rail.updateSettings(command))

    /** [[UserAccountApi.updateAvatar]] with its failure as a value. */
    def updateAvatar(image: AvatarImage): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateAvatar(image))

    /** [[UserAccountApi.deleteAvatar]] with its failure as a value. */
    def deleteAvatar(): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAvatar())

    /** [[UserAccountApi.emails]] with its failure as a value. */
    def emails(): Future[Either[CodebergError, Vector[Email]]] =
      exec.attempt(rail.emails())

    /** [[UserAccountApi.addEmails]] with its failure as a value. */
    def addEmails(first: EmailAddress, rest: EmailAddress*): Future[Either[CodebergError, Vector[Email]]] =
      exec.attempt(rail.addEmails(first, rest*))

    /** [[UserAccountApi.deleteEmails]] with its failure as a value. */
    def deleteEmails(first: EmailAddress, rest: EmailAddress*): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteEmails(first, rest*))

    /** [[UserAccountApi.repositories]] with its failure as a value. */
    def repositories(order: RepositoryOrder, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.repositories(order, params))

    /** [[UserAccountApi.createRepository]] with its failure as a value. */
    def createRepository(command: CreateRepository): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.createRepository(command))

    /** [[UserAccountApi.teams]] with its failure as a value. */
    def teams(params: PageParams): Future[Either[CodebergError, Page[Team]]] =
      exec.attempt(rail.teams(params))

  private def settingsRequest: CodebergRequest =
    read(SettingsOperation, settingsPath, Nil)

  private def updateSettingsRequest(command: UpdateUserSettings): CodebergRequest =
    write(
      UpdateSettingsOperation,
      HttpMethod.Patch,
      settingsPath,
      AccountOptionDto.renderSettings(command),
    )

  private def updateAvatarRequest(image: AvatarImage): CodebergRequest =
    write(
      UpdateAvatarOperation,
      HttpMethod.Post,
      avatarPath,
      AccountOptionDto.renderAvatar(image),
    )

  private def deleteAvatarRequest: CodebergRequest =
    remove(DeleteAvatarOperation, avatarPath)

  private def emailsRequest: CodebergRequest =
    read(EmailsOperation, emailsPath, Nil)

  private def addEmailsRequest(addresses: Vector[EmailAddress]): CodebergRequest =
    write(
      AddEmailsOperation,
      HttpMethod.Post,
      emailsPath,
      AccountOptionDto.renderEmails(addresses),
    )

  private def deleteEmailsRequest(addresses: Vector[EmailAddress]): CodebergRequest =
    removeWithBody(DeleteEmailsOperation, emailsPath, AccountOptionDto.renderEmails(addresses))

  private def repositoriesRequest(order: RepositoryOrder, params: PageParams): CodebergRequest =
    read(
      RepositoriesOperation,
      repositoriesPath,
      AccountQueries.paging(params) ++ AccountQueries.repositoryOrder(order),
    )

  private def createRepositoryRequest(command: CreateRepository): CodebergRequest =
    write(
      CreateRepositoryOperation,
      HttpMethod.Post,
      repositoriesPath,
      AccountOptionDto.renderRepository(command),
    )

  private def teamsRequest(params: PageParams): CodebergRequest =
    read(TeamsOperation, AccountRequests.path("teams"), AccountQueries.paging(params))

  private def settingsPath: List[String] =
    AccountRequests.path("settings")

  private def avatarPath: List[String] =
    AccountRequests.path("avatar")

  private def emailsPath: List[String] =
    AccountRequests.path("emails")

  private def repositoriesPath: List[String] =
    AccountRequests.path("repos")
