package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.organizations.actions.OrganizationActionApi
import com.worxbend.codeberg4s.organizations.wire.{OrganizationOptionDto, OrganizationQueries}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.admin.wire.RepositoryOptionDto
import com.worxbend.codeberg4s.repositories.admin.{CreateRepository, RepositoryActivity}
import com.worxbend.codeberg4s.users.account.AvatarImage
import com.worxbend.codeberg4s.users.social.BlockedUser
import com.worxbend.codeberg4s.users.{User, Username}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod}

import scala.concurrent.Future

import java.time.LocalDate

/** Organisation endpoints, together with the teams and the memberships that hang off them.
  *
  * Reached as `client.organizations`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[OrganizationApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==What is here, and what is on a neighbour==
  *
  * This class holds the organisation resource itself — reading it, creating it, editing it, renaming it, deleting it —
  * plus its members, its blocks, its repositories and its activity feed. Four sub-resources are large enough to have
  * classes of their own, reached from here:
  *
  *   - [[hooks]] — the organisation's webhooks.
  *   - [[labels]] — the organisation's shared label set.
  *   - [[teamAdmin]] — creating, changing and staffing teams. Named `teamAdmin` rather than `teams` only because
  *     [[teams]] is already the listing method on this class; there is no second concept behind the name.
  *   - [[quota]] — storage limits and what is using them.
  *
  * ==Most of this group needs a token, whatever the specification says==
  *
  * `docs/HAZARDS.md` §2 measured that the pinned spec declares `security` once, globally, with '''zero''' per-operation
  * overrides across all 506 operations. It therefore carries no per-endpoint authentication information at all, and the
  * "optional" in `docs/API_INVENTORY.md` is derived from path shape rather than from the spec. What was actually
  * measured against codeberg.org, and recorded in `golden/MANIFEST.md`:
  *
  *   - [[get]], [[list]] and [[repositories]] answer `200` anonymously — `golden/organization/org-single.json`,
  *     `org-list.json` and `org-repos-list.json` are those captures, and `org-labels-list.json` is the same for
  *     [[labels]].
  *   - [[members]], [[teams]] and [[userOrganizations]] answer `401 token is required` anonymously, even though the
  *     inventory marks all three "optional". The bodies are `golden/error/401-org-teams.json` and
  *     `golden/error/401-user-orgs.json`.
  *   - [[publicMembers]], [[getTeam]], [[teamMembers]] and [[teamRepositories]] were not probed. The three team
  *     operations describe membership of a private structure, so expect them to behave like [[teams]].
  *   - Every operation that writes, and every operation added after the first wave, needs a token by construction. None
  *     of them has a fixture, for the same reason: the harvest was anonymous.
  *
  * Configure a token unless a call is known to work without one on the instance being talked to. A `401` here is
  * ordinary, not exceptional.
  *
  * ==Failures==
  *
  * Every operation can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the organisation, team or account does not
  *     exist '''or''' is not visible to the configured credentials — Forgejo does not distinguish the two, on purpose —
  *     `401` when a token was required and none was sent (see above), and `403` when the token lacks the scope. `422`
  *     '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 captured Forgejo using both,
  *     so a caller checking only for `422` will miss half of them.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, at its position for a listing — `$[2].id` rather than `$`.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[OrgName]], [[TeamId]], [[com.worxbend.codeberg4s.users.Username]] — so a value that
  * would forge a path is rejected by its own smart constructor before a client is ever involved.
  *
  * [[isMember]] and [[isPublicMember]] are the two exceptions to the failure contract above, and they are exceptions in
  * one direction only: for those two, and only those two, a `404` is an answer rather than a failure. Each says so.
  *
  * ==Retries: nothing keyed by an organisation handle is ever repeated==
  *
  * Reads are [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]], as everywhere. Every '''write''' on this
  * class is [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], and one argument covers all of them: an
  * [[OrgName]] is not an identifier. [[rename]] is in this very class, and [[delete]] frees a handle for anybody to
  * take, so between a lost success and its retry the handle in the path can come to mean a '''different''' organisation
  * — and the retry would then block a user, conceal a member or clear an avatar somewhere nobody asked about. The bar
  * earlier waves set is that [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] requires the request to name
  * one instance-wide identifier the server never reuses; a handle that `rename` can move is the opposite of that.
  *
  * The neighbours are not bound by that, and say so where they differ: [[hooks]] and [[labels]] address their subject
  * by a row id, which a stale handle can only turn into a `404`, and [[teamAdmin]] is rooted at `/teams/{id}` for the
  * same reason.
  *
  * ==Paging==
  *
  * Every listing returns one [[com.worxbend.codeberg4s.paging.Page]], never a whole collection: `GET /orgs` reported
  * `x-total-count: 24159` on the day the fixture was captured. Whether another page exists is decided by the response's
  * `rel="next"` link and never by how many items came back — Forgejo silently clamps `limit` to its own maximum while
  * echoing the requested value, so a short page is not evidence of the end of the collection (`docs/HAZARDS.md` §5). A
  * page past the end is `200` with `[]`, not a `404`. `page` and `limit` are always sent together, because the fixture
  * manifest records list endpoints that ignore a lone `limit` and return everything.
  */
final class OrganizationApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationApi.Attempt = OrganizationApi.Attempt(this)

  /** The organisation's webhooks — `client.organizations.hooks`. */
  val hooks: OrganizationHookApi = OrganizationHookApi(pipeline)

  /** The organisation's shared label set — `client.organizations.labels`. */
  val labels: OrganizationLabelApi = OrganizationLabelApi(pipeline)

  /** Creating, changing and staffing teams — `client.organizations.teamAdmin`. See the class note on the name. */
  val teamAdmin: OrganizationTeamApi = OrganizationTeamApi(pipeline)

  /** Storage limits and what is using them — `client.organizations.quota`. */
  val quota: OrganizationQuotaApi = OrganizationQuotaApi(pipeline)

  /** Forgejo Actions scoped to the organisation: runners, secrets and variables. */
  val actions: OrganizationActionApi = OrganizationActionApi(pipeline)

  // --- the organisation itself ----------------------------------------------

  /** Reads one organisation — `GET /orgs/{org}`.
    *
    * Works anonymously on codeberg.org: `golden/organization/org-single.json` is `GET /orgs/forgejo` with no
    * credentials. The same account read as `GET /users/forgejo` answers with a [[com.worxbend.codeberg4s.users.User]]
    * body instead; see [[Organization]] for why both exist and which to prefer.
    *
    * '''Failures.''' The group contract above. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means the
    * payload carried no `id`, or a `name` that could not be a path segment.
    *
    * @param org
    *   the organisation handle as it appears in a Codeberg URL
    */
  def get(org: OrgName): Future[Organization] =
    pipeline.call(OrganizationApi.getRequest(org), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.organization)

  /** Lists every organisation on the instance — `GET /orgs`.
    *
    * Only organisations the configured credentials may see are returned; anonymously that is the public ones, which on
    * codeberg.org was 24159 of them when `golden/organization/org-list.json` was captured. This is the operation in
    * this group where treating a page as the whole collection hurts most.
    *
    * '''Failures.''' The group contract above. Works anonymously on codeberg.org.
    *
    * @param params
    *   the page to fetch and how many organisations it may hold
    */
  def list(params: PageParams): Future[Page[Organization]] =
    pipeline.callPage(OrganizationApi.listRequest(params), params)(using OrganizationDecoders.organizations)

  /** Creates an organisation — `POST /orgs`.
    *
    * '''Never retried.''' This library never repeats a `POST`, and the general reason is unusually concrete here: a
    * repeat after a lost success is not harmless, because the handle is unique — the second attempt answers `422` "user
    * already exists" and a caller reading that as "my request failed" would be wrong twice over. [[get]] on the handle
    * resolves what a transport failure left uncertain.
    *
    * '''Answers `201` with the created organisation''', including its [[Organization.id]] — which is worth storing,
    * because [[rename]] can move the handle.
    *
    * '''Who may.''' Forgejo restricts this by instance policy: an ordinary account may be forbidden from creating
    * organisations at all, which arrives as `403`.
    *
    * '''Failures.''' The group contract above. `422` covers a handle already taken, a handle the instance's own rules
    * reject, and a `visibility` an instance configured for public-only refuses.
    *
    * @param command
    *   the organisation to create
    */
  def create(command: CreateOrganization): Future[Organization] =
    pipeline.call(OrganizationApi.createRequest(command), RetryEligibility.Never)(using
      OrganizationDecoders.organization)

  /** Edits an organisation's profile — `PATCH /orgs/{org}`.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. See [[EditOrganization]] for
    * why the one flag is an `Option[Boolean]`, and note that the '''handle''' is not editable through this endpoint —
    * [[rename]] is.
    *
    * '''Never retried''', for the class-level reason: the request names the organisation by a handle [[rename]] can
    * move, so a repeat after a lost success could rewrite the profile of whatever now answers to that handle. There is
    * a second, independent reason to decline — a repeat would overwrite whatever the profile has become in the
    * meantime, and Forgejo offers no conditional-update header — which is the same argument
    * [[com.worxbend.codeberg4s.issues.IssueLabelApi.edit]] makes.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param command
    *   what to change; an empty command is a well-formed request that changes nothing
    */
  def edit(org: OrgName, command: EditOrganization): Future[Organization] =
    pipeline.call(OrganizationApi.editRequest(org, command), RetryEligibility.Never)(using
      OrganizationDecoders.organization)

  /** Deletes an organisation — `DELETE /orgs/{org}`.
    *
    * '''This takes the repositories with it.''' Forgejo removes the organisation and everything it owns; there is no
    * archive step and no undo, and the handle becomes available for anyone to claim.
    *
    * '''Never retried, and this is the sharpest case for it in the group.''' The request names its subject by a handle
    * that this very call frees. If the first attempt succeeded and its response was lost, the handle may by then belong
    * to a different organisation — and the retry would delete '''that''' one, with its repositories. A `404` after a
    * lost success is the good outcome; the bad one is a `204`. No retry policy can tell the two apart from here, so the
    * call is never repeated.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `403` is what Forgejo answers for an organisation that still owns
    * repositories on an instance configured to refuse that.
    *
    * @param org
    *   the organisation handle
    */
  def delete(org: OrgName): Future[Unit] =
    pipeline.callUnit(OrganizationApi.deleteRequest(org), RetryEligibility.Never)

  /** Renames an organisation — `POST /orgs/{org}/rename`.
    *
    * '''Every [[OrgName]] the caller is holding becomes wrong when this succeeds.''' The handle is the path segment
    * every other operation in this group takes, so a cached `OrgName`, a stored URL, a queued job carrying one, a
    * webhook target spelled with the old handle: all of them now address either nothing or somebody else. Forgejo
    * leaves no redirect behind at the API. Re-read the organisation with [[get]] under the new handle and replace
    * whatever was kept — and prefer [[Organization.id]] for anything that outlives one call, since that is the value a
    * rename does not touch.
    *
    * '''Never retried''', and for a reason worse than the class-level one. The old handle is freed by the rename
    * itself, so a repeat after a lost success renames '''whatever now holds the old name''' — which, if somebody
    * claimed it in between, is a different organisation being renamed to a handle the caller chose. A retry here can
    * take a third party's organisation away from them. Nothing about the response tells the two apart, so this call is
    * issued exactly once.
    *
    * '''Answers `204`''', with no body: the new organisation is not returned, so a caller who wants it reads [[get]]
    * with the new handle afterwards.
    *
    * '''Failures.''' The group contract above. `422` is what a handle already in use produces, and `403` a caller who
    * may not rename.
    *
    * @param org
    *   the handle the organisation has now
    * @param newName
    *   the handle it will have. Already validated as a path segment, which matters more than usual: after this call it
    *   '''is''' the path segment
    */
  def rename(org: OrgName, newName: OrgName): Future[Unit] =
    pipeline.callUnit(OrganizationApi.renameRequest(org, newName), RetryEligibility.Never)

  /** Replaces the organisation's avatar — `POST /orgs/{org}/avatar`.
    *
    * '''The image travels base64 inside a JSON body''', not as a file part: the spec declares the body as
    * `UpdateUserAvatarOption`, an object with one `image` property described as "image must be base64 encoded",
    * consumed as `application/json`. Reaching for [[com.worxbend.codeberg4s.core.RequestBody.Multipart]] here would
    * produce a request Forgejo rejects.
    *
    * '''The argument is [[com.worxbend.codeberg4s.users.account.AvatarImage]], which this group imports rather than
    * forks.''' `UpdateUserAvatarOption` is the '''same''' spec model `POST /user/avatar` takes, and that type already
    * covers it; `docs/LEDGER.md` calls a forked copy of a shared model a review-blocking defect. There is a second
    * avatar type in the codebase — [[com.worxbend.codeberg4s.repositories.admin.AvatarImage]], for the different
    * `UpdateRepoAvatarOption` model — and this endpoint deliberately uses the one whose spec model it actually sends.
    *
    * Nothing here judges the picture: which formats and sizes an instance accepts are deployment settings, and a limit
    * this library invented would be one a caller could not raise.
    *
    * '''Never retried''': a `POST`, and keyed by a handle [[rename]] can move.
    *
    * '''Answers `204`''', with no body — the new avatar URL is not returned, so a caller who needs it reads [[get]].
    *
    * '''Failures.''' The group contract above. An image the instance will not take — too large for its configured
    * maximum, or in a format its build cannot decode — comes back as `422` or `400`.
    *
    * @param org
    *   the organisation handle
    * @param image
    *   the avatar to upload
    */
  def updateAvatar(org: OrgName, image: AvatarImage): Future[Unit] =
    pipeline.callUnit(OrganizationApi.updateAvatarRequest(org, image), RetryEligibility.Never)

  /** Removes the organisation's avatar — `DELETE /orgs/{org}/avatar`.
    *
    * The organisation falls back to whatever the instance generates for an account without one.
    *
    * '''Never retried''', for the class-level reason: the only thing this request names is a handle [[rename]] can
    * move, so a repeat after a lost success could clear the avatar of a different organisation. Emptying an avatar
    * twice would otherwise be a natural candidate for retrying — the argument that rules it out is about '''whose'''
    * avatar, not about how many times.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    */
  def deleteAvatar(org: OrgName): Future[Unit] =
    pipeline.callUnit(OrganizationApi.deleteAvatarRequest(org), RetryEligibility.Never)

  // --- repositories ---------------------------------------------------------

  /** Lists an organisation's repositories — `GET /orgs/{org}/repos`.
    *
    * The elements are [[com.worxbend.codeberg4s.repositories.Repository]] values, the model `client.repos` returns —
    * `golden/organization/org-repos-list.json` is a capture of this endpoint whose elements carry the full repository
    * payload, owner object included. Only repositories the configured credentials may see are listed, so the same call
    * answers differently for an anonymous client and for a member's token.
    *
    * '''Failures.''' The group contract above, with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] meaning an
    * element carried no `id`, `name` or `owner`. Works anonymously on codeberg.org.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many repositories it may hold
    */
  def repositories(org: OrgName, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(OrganizationApi.repositoriesRequest(org, params), params)(using OrganizationDecoders.repositories)

  /** Creates a repository owned by the organisation — `POST /orgs/{org}/repos`.
    *
    * The command is [[com.worxbend.codeberg4s.repositories.admin.CreateRepository]], the same value
    * `client.repos.admin.create` takes: `CreateRepoOption` is one model, and the only difference between the two
    * endpoints is who ends up owning the result. `docs/LEDGER.md` calls a forked copy of a shared model a
    * review-blocking defect, so this operation imports it.
    *
    * '''Without [[com.worxbend.codeberg4s.repositories.admin.CreateRepository.initialised]] the repository has no
    * commits''' and no default branch, which makes several other endpoints answer `404` until something is pushed.
    *
    * '''Never retried.''' A `POST`, and a repeat after a lost success is a second create that answers `409` or `422`
    * for a name now taken — which reads like a failure and is not one. [[repositories]] resolves it.
    *
    * '''Answers `201` with the created repository.'''
    *
    * '''Failures.''' The group contract above; this operation is one of the few where the spec declares `400` rather
    * than `422` for a bad request, which is `docs/HAZARDS.md` §4 in the spec's own text rather than in a capture.
    *
    * @param org
    *   the organisation that will own the repository
    * @param command
    *   the repository to create
    */
  def createRepository(org: OrgName, command: CreateRepository): Future[Repository] =
    pipeline.call(OrganizationApi.createRepositoryRequest(org, command), RetryEligibility.Never)(using
      OrganizationDecoders.repository)

  /** Creates a repository owned by the organisation, at the '''deprecated''' path — `POST /org/{org}/repos`.
    *
    * '''Prefer [[createRepository]].''' This is the same operation at `/org/{org}/repos` — singular `org` — which
    * Forgejo keeps for compatibility with clients written before the path was regularised. The spec's own operation id
    * says so: `createOrgRepoDeprecated`. It is implemented here because a Forgejo release removing it is a thing a
    * caller may need to detect, and because an operation this library silently omitted would be one nobody could tell
    * was missing.
    *
    * Everything else — the body, the ownership, the `201`, the retry decision — is [[createRepository]]'s. A future
    * Forgejo that has dropped the route answers `404`, which on this path means "the route is gone" as readily as "no
    * such organisation".
    *
    * '''Never retried''', for [[createRepository]]'s reason.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation that will own the repository
    * @param command
    *   the repository to create
    */
  def createRepositoryDeprecated(org: OrgName, command: CreateRepository): Future[Repository] =
    pipeline.call(OrganizationApi.createRepositoryDeprecatedRequest(org, command), RetryEligibility.Never)(using
      OrganizationDecoders.repository)

  // --- members --------------------------------------------------------------

  /** Lists an organisation's members — `GET /orgs/{org}/members`.
    *
    * This is the full membership, including members who chose to keep their membership concealed; [[publicMembers]] is
    * the subset that is public. Reading it needs credentials that may see the organisation's membership.
    *
    * '''`401` is the ordinary anonymous outcome''', not an exceptional one: `golden/MANIFEST.md` records that this path
    * answers `401 token is required` to an anonymous caller on codeberg.org, with a body identical to
    * `golden/error/401-token-required.json`, even though `docs/API_INVENTORY.md` marks it "optional" — a column derived
    * from path shape, for the reason `docs/HAZARDS.md` §2 gives.
    *
    * '''Failures.''' The group contract above, with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] meaning an
    * element carried no `id` or `login`.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many members it may hold
    */
  def members(org: OrgName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(OrganizationApi.membersRequest(org, params), params)(using OrganizationDecoders.users)

  /** Lists the members who have made their membership public — `GET /orgs/{org}/public_members`.
    *
    * A member is concealed by default on Forgejo and appears here only after publicising their membership, so this
    * listing is normally much shorter than [[members]] and is never longer.
    *
    * '''Not probed.''' No golden fixture exists for this path: the anonymous capture session recorded `401` for
    * [[members]] and did not separately probe this one. Treat a `401` as ordinary here too until an instance proves
    * otherwise.
    *
    * '''Failures.''' As [[members]].
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many members it may hold
    */
  def publicMembers(org: OrgName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(OrganizationApi.publicMembersRequest(org, params), params)(using OrganizationDecoders.users)

  /** Asks whether an account is a member — `GET /orgs/{org}/members/{username}`.
    *
    * '''This endpoint answers with a status, not a body.''' `204` means member, `404` means not a member, and there is
    * no payload either way. So this is one of two operations in this class that read a `404` as an answer rather than
    * as a failure — it becomes `false` on the success channel, and every other status keeps travelling on the error
    * channel exactly as it would elsewhere. [[com.worxbend.codeberg4s.pulls.PullRequestApi.isMerged]] is the pattern.
    *
    * '''The `404` is genuinely ambiguous, and the ambiguity is Forgejo's.''' The same status answers "not a member",
    * "no such organisation" and "no such account". A caller who needs to tell them apart reads [[get]], which
    * distinguishes the middle one.
    *
    * '''There is a `303` in the spec, and it is not a failure either.''' Forgejo redirects a caller who may not see the
    * full membership to `/orgs/{org}/public_members/{username}`, which then answers the narrower question. The
    * transport follows redirects, so what arrives here is the redirected `204` or `404` — meaning a `true` from this
    * method can mean "a public member" rather than "a member", when the credentials could not see more.
    * [[isPublicMember]] asks the narrow question deliberately.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]] like any other `GET`. A `404`
    * is not a retryable status, so the `false` answer is never reached by way of an exhausted retry budget.
    *
    * '''Failures.''' The group contract above, minus `404` and minus the decoding case — nothing is parsed. `401` and
    * `403` still arrive as [[com.worxbend.codeberg4s.CodebergError.Api]], so an unreadable organisation does not
    * masquerade as a non-member.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account handle
    */
  def isMember(org: OrgName, username: Username): Future[Boolean] =
    OrganizationApi.probe(pipeline, OrganizationApi.isMemberRequest(org, username))

  /** Asks whether an account is a '''public''' member — `GET /orgs/{org}/public_members/{username}`.
    *
    * The same status-as-answer contract as [[isMember]]: `204` is `true`, `404` is `false`, everything else fails.
    * Unlike [[isMember]] there is no redirect to reason about, and the question is narrower — a member who has
    * concealed their membership answers `false` here and `true` there.
    *
    * '''Failures.''' As [[isMember]].
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account handle
    */
  def isPublicMember(org: OrgName, username: Username): Future[Boolean] =
    OrganizationApi.probe(pipeline, OrganizationApi.isPublicMemberRequest(org, username))

  /** Removes an account from the organisation — `DELETE /orgs/{org}/members/{username}`.
    *
    * '''This removes them from every team as well''', because team membership is a subset of organisation membership.
    * Removing someone from one team is [[teamAdmin]]'s `removeMember`.
    *
    * '''Never retried''', for the class-level reason: the request names the organisation by a handle [[rename]] can
    * move, and what it does is remove a person's access. A repeat after a lost success could evict an account from an
    * organisation nobody asked about.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account to remove
    */
  def removeMember(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationApi.removeMemberRequest(org, username), RetryEligibility.Never)

  /** Makes a membership public — `PUT /orgs/{org}/public_members/{username}`.
    *
    * The account then appears in [[publicMembers]] and on the organisation's page. Forgejo lets a member publicise
    * their own membership and an owner publicise anyone's.
    *
    * '''Never retried''', for the class-level reason. Setting a flag twice is setting it once, so the '''repetition'''
    * is harmless; what is not is the handle, which [[rename]] can move between the lost success and the retry — and
    * this call publishes a fact about a person's affiliation, which is not something to publish about the wrong
    * organisation.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `403` is what Forgejo answers for a caller publicising somebody else's
    * membership without the standing to.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account whose membership becomes public
    */
  def publicizeMember(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationApi.publicizeMemberRequest(org, username), RetryEligibility.Never)

  /** Conceals a membership — `DELETE /orgs/{org}/public_members/{username}`.
    *
    * The account stays a member and stops appearing in [[publicMembers]]. This is '''not''' [[removeMember]]: nothing
    * about the account's access changes.
    *
    * '''Never retried''', for [[publicizeMember]]'s reason.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account whose membership is concealed
    */
  def concealMember(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationApi.concealMemberRequest(org, username), RetryEligibility.Never)

  // --- blocks ---------------------------------------------------------------

  /** Lists the accounts an organisation has blocked — `GET /orgs/{org}/list_blocked`.
    *
    * '''The entries do not name the accounts.''' Forgejo's `BlockedUser` model declares `block_id` and `created_at` and
    * nothing else; see [[BlockedUser]] for why that is reported as-is rather than patched over with a guessed field.
    *
    * '''Failures.''' The group contract above. The spec declares only a `200` for this operation, which means nothing:
    * `docs/HAZARDS.md` §2 measured the spec's error coverage to be decorative, and a `404` for an organisation that
    * does not exist is what any of these paths does.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def blockedUsers(org: OrgName, params: PageParams): Future[Page[BlockedUser]] =
    pipeline.callPage(OrganizationApi.blockedUsersRequest(org, params), params)(using OrganizationDecoders.blockedUsers)

  /** Blocks an account — `PUT /orgs/{org}/block/{username}`.
    *
    * A blocked account cannot follow the organisation, open issues on its repositories or interact with it. Forgejo
    * removes them from the organisation as a side effect if they were a member, which is why this is not simply a flag.
    *
    * '''Never retried''', for the class-level reason, and the side effect above is why it matters here: the handle can
    * move between a lost success and a retry, and the retry would then evict somebody from a different organisation.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `422` is what Forgejo answers for an account it will not let the
    * organisation block — an owner, or one already blocked.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account to block
    */
  def blockUser(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationApi.blockUserRequest(org, username), RetryEligibility.Never)

  /** Lifts a block — `PUT /orgs/{org}/unblock/{username}`.
    *
    * '''A `PUT`, not a `DELETE`, and that is the API's own shape.''' The block is lifted by naming the account at a
    * second path rather than by deleting the record [[blockedUsers]] reports — which is why [[BlockId]] is never handed
    * to anything.
    *
    * '''Never retried''', for [[blockUser]]'s reason.
    *
    * '''Answers `204`''', and does not restore a membership the block removed.
    *
    * '''Failures.''' The group contract above. `422` is what an account that is not blocked produces.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account to unblock
    */
  def unblockUser(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationApi.unblockUserRequest(org, username), RetryEligibility.Never)

  // --- teams ----------------------------------------------------------------

  /** Lists an organisation's teams — `GET /orgs/{org}/teams`.
    *
    * '''`401` is the ordinary anonymous outcome.''' `golden/MANIFEST.md` records this exact path answering
    * `401 token is required`, and `golden/error/401-org-teams.json` is that body — which is why no team fixture exists
    * anywhere in this repository and why [[Team]] is derived from the pinned spec rather than from a capture.
    *
    * '''Failures.''' The group contract above, with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] meaning an
    * element carried no `name`, or an `id` that was absent or not a positive number.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many teams it may hold
    */
  def teams(org: OrgName, params: PageParams): Future[Page[Team]] =
    pipeline.callPage(OrganizationApi.teamsRequest(org, params), params)(using OrganizationDecoders.teams)

  /** Reads one team — `GET /teams/{id}`.
    *
    * '''The path is rooted at the instance, not at the organisation.''' A team is addressed by its numeric id and never
    * by its name, because two organisations may each own a team called `owners`; see [[TeamId]]. The organisation a
    * team belongs to comes back inside the payload, on [[Team.organization]].
    *
    * '''Failures.''' The group contract above. Expect `401` without a token, for the reason [[teams]] gives.
    *
    * @param id
    *   the team's instance-wide identifier
    */
  def getTeam(id: TeamId): Future[Team] =
    pipeline.call(OrganizationApi.getTeamRequest(id), RetryEligibility.IdempotentOnly)(using OrganizationDecoders.team)

  /** Lists a team's members — `GET /teams/{id}/members`.
    *
    * '''Failures.''' As [[members]], and expect `401` without a token for the reason [[teams]] gives.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param params
    *   the page to fetch and how many members it may hold
    */
  def teamMembers(id: TeamId, params: PageParams): Future[Page[User]] =
    pipeline.callPage(OrganizationApi.teamMembersRequest(id, params), params)(using OrganizationDecoders.users)

  /** Lists the repositories a team reaches — `GET /teams/{id}/repos`.
    *
    * A team whose [[Team.includesAllRepositories]] is `true` lists the organisation's entire repository set here, so
    * this listing can be long even when the team is small.
    *
    * '''Failures.''' As [[repositories]], and expect `401` without a token for the reason [[teams]] gives.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param params
    *   the page to fetch and how many repositories it may hold
    */
  def teamRepositories(id: TeamId, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(OrganizationApi.teamRepositoriesRequest(id, params), params)(using
      OrganizationDecoders.repositories)

  // --- feeds and cross-account reads ----------------------------------------

  /** Lists an organisation's activity feed — `GET /orgs/{org}/activities/feeds`.
    *
    * The entries are [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity]] values — Forgejo's single
    * `Activity` model, which this group imports rather than forks; see [[OrganizationDecoders]] for why the
    * repository-flavoured name is still the right type.
    *
    * '''It is not an audit log.''' An entry exists because Forgejo decided the event was worth showing a human; there
    * is no promise that every state change produces one, and
    * [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity.content]] carries a different shape per action
    * type.
    *
    * '''Private entries depend on the credentials.''' An anonymous or unprivileged caller sees fewer entries rather
    * than a `403`, so a short feed is not evidence that little happened.
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param date
    *   the calendar day to report, absent for the instance's own default window. A day and not an instant: the spec
    *   declares `format: date`, so which day it is depends on the instance's clock
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def activities(org: OrgName, date: Option[LocalDate], params: PageParams): Future[Page[RepositoryActivity]] =
    pipeline.callPage(OrganizationApi.activitiesRequest(org, date, params), params)(using
      OrganizationDecoders.activities)

  /** Lists the organisations an account belongs to — `GET /users/{username}/orgs`.
    *
    * '''`401` is the ordinary anonymous outcome.''' `golden/error/401-user-orgs.json` is a verbatim capture of
    * `GET /users/earl-warren/orgs?page=1&limit=3` answering `401 token is required` with no credentials, even though
    * `docs/API_INVENTORY.md` marks the operation "optional". That column is derived from path shape, not from the
    * specification — `docs/HAZARDS.md` §2 measured that the spec declares `security` globally with zero per-operation
    * overrides and so carries no per-endpoint authentication information at all. The manifest records the same
    * behaviour for `/users/{u}/followers` and `/users/{u}/starred`.
    *
    * The argument is a [[com.worxbend.codeberg4s.users.Username]] and not an [[OrgName]]: this operation names a
    * '''person''' and asks what they belong to.
    *
    * '''Failures.''' The group contract above, with `404` for an account that does not exist and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when an element carried no `id` or no usable `name`.
    *
    * @param username
    *   the account handle as it appears in a Codeberg URL
    * @param params
    *   the page to fetch and how many organisations it may hold
    */
  def userOrganizations(username: Username, params: PageParams): Future[Page[Organization]] =
    pipeline.callPage(OrganizationApi.userOrganizationsRequest(username, params), params)(using
      OrganizationDecoders.organizations)

  /** Lists the organisations the '''authenticated''' account belongs to — `GET /user/orgs`.
    *
    * The counterpart to [[userOrganizations]] that names nobody: the token decides whose organisations these are, which
    * is why the path is `/user/orgs` — singular `user` — and takes no handle. It is the only operation in this group
    * whose result depends on the credentials rather than on an argument, and the only one that is useless without a
    * token: the spec declares `401` for it explicitly, which given how little the spec says about authentication
    * anywhere is worth noticing.
    *
    * It also sees more than [[userOrganizations]] would for the same account: concealed memberships are included,
    * because the caller is the member.
    *
    * '''Failures.''' The group contract above, with `401` the expected outcome without a token.
    *
    * @param params
    *   the page to fetch and how many organisations it may hold
    */
  def currentUserOrganizations(params: PageParams): Future[Page[Organization]] =
    pipeline.callPage(OrganizationApi.currentUserOrganizationsRequest(params), params)(using
      OrganizationDecoders.organizations)

  /** Reads what one account may do in one organisation — `GET /users/{username}/orgs/{org}/permissions`.
    *
    * '''The effective answer, not a role''': Forgejo folds ownership, team membership and site administration into five
    * booleans and reports the result, with no field saying which of them granted what. See [[OrganizationPermissions]].
    *
    * '''Absent flags read as `false`''', which is the safe direction for a permission — see
    * [[com.worxbend.codeberg4s.organizations.wire.OrganizationPermissionsDto]].
    *
    * '''Failures.''' The group contract above. `403` is what Forgejo answers a caller asking about somebody else
    * without the standing to; `404` covers both the account and the organisation, and does not say which.
    *
    * @param username
    *   the account being asked about
    * @param org
    *   the organisation the question is about
    */
  def userPermissions(username: Username, org: OrgName): Future[OrganizationPermissions] =
    pipeline.call(OrganizationApi.userPermissionsRequest(username, org), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.permissions)

/** The requests this group issues, its operation ids, and its typed rail. */
object OrganizationApi:

  /** The stable operation id [[OrganizationApi.get]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val GetOperation: String = "orgs.get"

  /** The stable operation id of [[OrganizationApi.list]]. */
  val ListOperation: String = "orgs.list"

  /** The stable operation id of [[OrganizationApi.create]]. */
  val CreateOperation: String = "orgs.create"

  /** The stable operation id of [[OrganizationApi.edit]]. */
  val EditOperation: String = "orgs.edit"

  /** The stable operation id of [[OrganizationApi.delete]]. Worth alerting on by itself: it destroys repositories. */
  val DeleteOperation: String = "orgs.delete"

  /** The stable operation id of [[OrganizationApi.rename]]. Worth alerting on: it invalidates every cached handle. */
  val RenameOperation: String = "orgs.rename"

  /** The stable operation id of [[OrganizationApi.updateAvatar]]. */
  val UpdateAvatarOperation: String = "orgs.avatar.update"

  /** The stable operation id of [[OrganizationApi.deleteAvatar]]. */
  val DeleteAvatarOperation: String = "orgs.avatar.delete"

  /** The stable operation id of [[OrganizationApi.repositories]]. */
  val RepositoriesOperation: String = "orgs.repos.list"

  /** The stable operation id of [[OrganizationApi.createRepository]]. */
  val CreateRepositoryOperation: String = "orgs.repos.create"

  /** The stable operation id of [[OrganizationApi.createRepositoryDeprecated]], which is a different route and
    * therefore a different id: a caller alerting on the deprecated path must be able to see it separately.
    */
  val CreateRepositoryDeprecatedOperation: String = "orgs.repos.createDeprecated"

  /** The stable operation id of [[OrganizationApi.members]]. */
  val MembersOperation: String = "orgs.members.list"

  /** The stable operation id of [[OrganizationApi.isMember]]. */
  val IsMemberOperation: String = "orgs.members.check"

  /** The stable operation id of [[OrganizationApi.removeMember]]. */
  val RemoveMemberOperation: String = "orgs.members.remove"

  /** The stable operation id of [[OrganizationApi.publicMembers]]. */
  val PublicMembersOperation: String = "orgs.publicMembers.list"

  /** The stable operation id of [[OrganizationApi.isPublicMember]]. */
  val IsPublicMemberOperation: String = "orgs.publicMembers.check"

  /** The stable operation id of [[OrganizationApi.publicizeMember]]. */
  val PublicizeMemberOperation: String = "orgs.publicMembers.add"

  /** The stable operation id of [[OrganizationApi.concealMember]]. */
  val ConcealMemberOperation: String = "orgs.publicMembers.remove"

  /** The stable operation id of [[OrganizationApi.blockedUsers]]. */
  val BlockedUsersOperation: String = "orgs.blocks.list"

  /** The stable operation id of [[OrganizationApi.blockUser]]. */
  val BlockUserOperation: String = "orgs.blocks.add"

  /** The stable operation id of [[OrganizationApi.unblockUser]]. */
  val UnblockUserOperation: String = "orgs.blocks.remove"

  /** The stable operation id of [[OrganizationApi.teams]]. */
  val TeamsOperation: String = "orgs.teams.list"

  /** The stable operation id of [[OrganizationApi.getTeam]]. */
  val GetTeamOperation: String = "orgs.teams.get"

  /** The stable operation id of [[OrganizationApi.teamMembers]]. */
  val TeamMembersOperation: String = "orgs.teams.members.list"

  /** The stable operation id of [[OrganizationApi.teamRepositories]]. */
  val TeamRepositoriesOperation: String = "orgs.teams.repos.list"

  /** The stable operation id of [[OrganizationApi.activities]]. */
  val ActivitiesOperation: String = "orgs.activities.list"

  /** The stable operation id of [[OrganizationApi.userOrganizations]]. */
  val UserOrganizationsOperation: String = "orgs.userOrgs.list"

  /** The stable operation id of [[OrganizationApi.currentUserOrganizations]]. */
  val CurrentUserOrganizationsOperation: String = "orgs.currentUserOrgs.list"

  /** The stable operation id of [[OrganizationApi.userPermissions]]. */
  val UserPermissionsOperation: String = "orgs.userPermissions.get"

  /** The status Forgejo answers when the subject of a membership probe is not a member.
    *
    * A `404` from those two endpoints is an answer rather than a failure, and they are the only place in this class
    * that reads a status that way. Named rather than written inline so the pattern below says why it exists;
    * [[com.worxbend.codeberg4s.pulls.PullRequestApi]] does the same for its merge probe.
    */
  private val NotAMember: Int = 404

  /** The typed rail of [[OrganizationApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.organizations.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both. The four
    * sub-resources have typed rails of their own — `client.organizations.hooks.attempt` and the rest.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationApi)(using exec: Exec[Future]):

    /** [[OrganizationApi.get]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def get(org: OrgName): Future[Either[CodebergError, Organization]] =
      exec.attempt(rail.get(org))

    /** [[OrganizationApi.list]] with its failure as a value. */
    def list(params: PageParams): Future[Either[CodebergError, Page[Organization]]] =
      exec.attempt(rail.list(params))

    /** [[OrganizationApi.create]] with its failure as a value. */
    def create(command: CreateOrganization): Future[Either[CodebergError, Organization]] =
      exec.attempt(rail.create(command))

    /** [[OrganizationApi.edit]] with its failure as a value. */
    def edit(org: OrgName, command: EditOrganization): Future[Either[CodebergError, Organization]] =
      exec.attempt(rail.edit(org, command))

    /** [[OrganizationApi.delete]] with its failure as a value. */
    def delete(org: OrgName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(org))

    /** [[OrganizationApi.rename]] with its failure as a value. Every cached [[OrgName]] is invalid on a `Right`. */
    def rename(org: OrgName, newName: OrgName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.rename(org, newName))

    /** [[OrganizationApi.updateAvatar]] with its failure as a value. */
    def updateAvatar(org: OrgName, image: AvatarImage): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateAvatar(org, image))

    /** [[OrganizationApi.deleteAvatar]] with its failure as a value. */
    def deleteAvatar(org: OrgName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAvatar(org))

    /** [[OrganizationApi.repositories]] with its failure as a value. */
    def repositories(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.repositories(org, params))

    /** [[OrganizationApi.createRepository]] with its failure as a value. */
    def createRepository(org: OrgName, command: CreateRepository): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.createRepository(org, command))

    /** [[OrganizationApi.createRepositoryDeprecated]] with its failure as a value. */
    def createRepositoryDeprecated(
        org: OrgName,
        command: CreateRepository,
    ): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.createRepositoryDeprecated(org, command))

    /** [[OrganizationApi.members]] with its failure as a value. */
    def members(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.members(org, params))

    /** [[OrganizationApi.publicMembers]] with its failure as a value. */
    def publicMembers(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.publicMembers(org, params))

    /** [[OrganizationApi.isMember]] with its failure as a value. The `404` that means "not a member" is still a
      * `Right(false)` here, not a `Left`.
      */
    def isMember(org: OrgName, username: Username): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isMember(org, username))

    /** [[OrganizationApi.isPublicMember]] with its failure as a value, on [[isMember]]'s terms. */
    def isPublicMember(org: OrgName, username: Username): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isPublicMember(org, username))

    /** [[OrganizationApi.removeMember]] with its failure as a value. */
    def removeMember(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeMember(org, username))

    /** [[OrganizationApi.publicizeMember]] with its failure as a value. */
    def publicizeMember(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.publicizeMember(org, username))

    /** [[OrganizationApi.concealMember]] with its failure as a value. */
    def concealMember(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.concealMember(org, username))

    /** [[OrganizationApi.blockedUsers]] with its failure as a value. */
    def blockedUsers(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[BlockedUser]]] =
      exec.attempt(rail.blockedUsers(org, params))

    /** [[OrganizationApi.blockUser]] with its failure as a value. */
    def blockUser(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.blockUser(org, username))

    /** [[OrganizationApi.unblockUser]] with its failure as a value. */
    def unblockUser(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unblockUser(org, username))

    /** [[OrganizationApi.teams]] with its failure as a value. */
    def teams(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[Team]]] =
      exec.attempt(rail.teams(org, params))

    /** [[OrganizationApi.getTeam]] with its failure as a value. */
    def getTeam(id: TeamId): Future[Either[CodebergError, Team]] =
      exec.attempt(rail.getTeam(id))

    /** [[OrganizationApi.teamMembers]] with its failure as a value. */
    def teamMembers(id: TeamId, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.teamMembers(id, params))

    /** [[OrganizationApi.teamRepositories]] with its failure as a value. */
    def teamRepositories(id: TeamId, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.teamRepositories(id, params))

    /** [[OrganizationApi.activities]] with its failure as a value. */
    def activities(
        org: OrgName,
        date: Option[LocalDate],
        params: PageParams,
    ): Future[Either[CodebergError, Page[RepositoryActivity]]] =
      exec.attempt(rail.activities(org, date, params))

    /** [[OrganizationApi.userOrganizations]] with its failure as a value. */
    def userOrganizations(
        username: Username,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Organization]]] =
      exec.attempt(rail.userOrganizations(username, params))

    /** [[OrganizationApi.currentUserOrganizations]] with its failure as a value. */
    def currentUserOrganizations(params: PageParams): Future[Either[CodebergError, Page[Organization]]] =
      exec.attempt(rail.currentUserOrganizations(params))

    /** [[OrganizationApi.userPermissions]] with its failure as a value. */
    def userPermissions(
        username: Username,
        org: OrgName,
    ): Future[Either[CodebergError, OrganizationPermissions]] =
      exec.attempt(rail.userPermissions(username, org))

  /** Runs a status-only membership probe: `204` is `true`, `404` is `false`, everything else fails.
    *
    * Written once because [[OrganizationApi.isMember]] and [[OrganizationApi.isPublicMember]] are the same shape, and a
    * second copy that quietly widened the accepted status would be indistinguishable from one that did not.
    */
  private def probe(pipeline: ApiPipeline[Future], request: CodebergRequest)(using
      exec: Exec[Future]): Future[Boolean] =
    val asked = pipeline.callUnit(request, RetryEligibility.IdempotentOnly)

    exec.flatMap(exec.attempt(asked)):
      case Right(_)                                  => exec.pure(true)
      case Left(CodebergError.Api(_, NotAMember, _)) => exec.pure(false)
      case Left(error)                               => exec.raise(error)

  private def getRequest(org: OrgName): CodebergRequest =
    read(GetOperation, OrganizationRequests.organizationPath(org), Nil)

  private def listRequest(params: PageParams): CodebergRequest =
    read(ListOperation, List(OrganizationRequests.OrgsSegment), window(params))

  private def createRequest(command: CreateOrganization): CodebergRequest =
    write(
      CreateOperation,
      HttpMethod.Post,
      List(OrganizationRequests.OrgsSegment),
      OrganizationOptionDto.renderCreate(command),
    )

  private def editRequest(org: OrgName, command: EditOrganization): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      OrganizationRequests.organizationPath(org),
      OrganizationOptionDto.renderEdit(command),
    )

  private def deleteRequest(org: OrgName): CodebergRequest =
    bodiless(DeleteOperation, HttpMethod.Delete, OrganizationRequests.organizationPath(org))

  private def renameRequest(org: OrgName, newName: OrgName): CodebergRequest =
    write(
      RenameOperation,
      HttpMethod.Post,
      OrganizationRequests.organizationPath(org) :+ "rename",
      OrganizationOptionDto.renderRename(newName),
    )

  private def updateAvatarRequest(org: OrgName, image: AvatarImage): CodebergRequest =
    write(
      UpdateAvatarOperation,
      HttpMethod.Post,
      avatarPath(org),
      OrganizationOptionDto.renderAvatar(image),
    )

  private def deleteAvatarRequest(org: OrgName): CodebergRequest =
    bodiless(DeleteAvatarOperation, HttpMethod.Delete, avatarPath(org))

  private def repositoriesRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(RepositoriesOperation, reposPath(org), window(params))

  private def createRepositoryRequest(org: OrgName, command: CreateRepository): CodebergRequest =
    write(
      CreateRepositoryOperation,
      HttpMethod.Post,
      reposPath(org),
      RepositoryOptionDto.renderCreate(command),
    )

  /** The deprecated route is `/org/{org}/repos` — singular, and therefore not
    * [[OrganizationRequests.organizationPath]].
    */
  private def createRepositoryDeprecatedRequest(org: OrgName, command: CreateRepository): CodebergRequest =
    write(
      CreateRepositoryDeprecatedOperation,
      HttpMethod.Post,
      List("org", org.value, "repos"),
      RepositoryOptionDto.renderCreate(command),
    )

  private def membersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(MembersOperation, membersPath(org), window(params))

  private def isMemberRequest(org: OrgName, username: Username): CodebergRequest =
    read(IsMemberOperation, memberPath(org, username), Nil)

  private def removeMemberRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(RemoveMemberOperation, HttpMethod.Delete, memberPath(org, username))

  private def publicMembersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(PublicMembersOperation, publicMembersPath(org), window(params))

  private def isPublicMemberRequest(org: OrgName, username: Username): CodebergRequest =
    read(IsPublicMemberOperation, publicMemberPath(org, username), Nil)

  private def publicizeMemberRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(PublicizeMemberOperation, HttpMethod.Put, publicMemberPath(org, username))

  private def concealMemberRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(ConcealMemberOperation, HttpMethod.Delete, publicMemberPath(org, username))

  private def blockedUsersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(
      BlockedUsersOperation,
      OrganizationRequests.organizationPath(org) :+ "list_blocked",
      window(params),
    )

  private def blockUserRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(
      BlockUserOperation,
      HttpMethod.Put,
      OrganizationRequests.organizationPath(org) ++ List("block", username.value),
    )

  private def unblockUserRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(
      UnblockUserOperation,
      HttpMethod.Put,
      OrganizationRequests.organizationPath(org) ++ List("unblock", username.value),
    )

  private def teamsRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(
      TeamsOperation,
      OrganizationRequests.organizationPath(org) :+ OrganizationRequests.TeamsSegment,
      window(params),
    )

  private def getTeamRequest(id: TeamId): CodebergRequest =
    read(GetTeamOperation, OrganizationRequests.teamPath(id), Nil)

  private def teamMembersRequest(id: TeamId, params: PageParams): CodebergRequest =
    read(TeamMembersOperation, OrganizationRequests.teamPath(id) :+ "members", window(params))

  private def teamRepositoriesRequest(id: TeamId, params: PageParams): CodebergRequest =
    read(TeamRepositoriesOperation, OrganizationRequests.teamPath(id) :+ "repos", window(params))

  private def activitiesRequest(org: OrgName, date: Option[LocalDate], params: PageParams): CodebergRequest =
    read(
      ActivitiesOperation,
      OrganizationRequests.organizationPath(org) ++ List("activities", "feeds"),
      OrganizationQueries.activities(date, params),
    )

  private def userOrganizationsRequest(username: Username, params: PageParams): CodebergRequest =
    read(
      UserOrganizationsOperation,
      OrganizationRequests.userPath(username) :+ OrganizationRequests.OrgsSegment,
      window(params),
    )

  private def currentUserOrganizationsRequest(params: PageParams): CodebergRequest =
    read(
      CurrentUserOrganizationsOperation,
      List("user", OrganizationRequests.OrgsSegment),
      window(params),
    )

  private def userPermissionsRequest(username: Username, org: OrgName): CodebergRequest =
    read(
      UserPermissionsOperation,
      OrganizationRequests.userPath(username) ++ List(OrganizationRequests.OrgsSegment, org.value, "permissions"),
      Nil,
    )

  private def avatarPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ "avatar"

  private def reposPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ "repos"

  private def membersPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ "members"

  private def memberPath(org: OrgName, username: Username): List[String] =
    membersPath(org) :+ username.value

  private def publicMembersPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ "public_members"

  private def publicMemberPath(org: OrgName, username: Username): List[String] =
    publicMembersPath(org) :+ username.value

  /** The `page` and `limit` parameters, in the order Forgejo's own `Link` header writes them.
    *
    * Delegates to [[com.worxbend.codeberg4s.organizations.wire.OrganizationQueries.paging]] rather than spelling the
    * two names again: `page` and `limit` are wire spellings, and rule 4 of
    * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once.
    */
  private def window(params: PageParams): List[(String, String)] =
    OrganizationQueries.paging(params)
