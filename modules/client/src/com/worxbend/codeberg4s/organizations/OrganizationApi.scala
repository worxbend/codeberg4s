package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.Username

import scala.concurrent.Future

/** Organisation endpoints, together with the teams and the memberships that hang off them.
  *
  * Reached as `client.organizations`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[OrganizationApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * Every operation here is a `GET`, so every one of them is retried under
  * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. This group creates nothing, edits nothing and
  * deletes nothing; the mutating half of Forgejo's organisation surface is deliberately out of scope.
  *
  * ==Most of this group needs a token, whatever the specification says==
  *
  * `docs/HAZARDS.md` §2 measured that the pinned spec declares `security` once, globally, with '''zero''' per-operation
  * overrides across all 506 operations. It therefore carries no per-endpoint authentication information at all, and the
  * "optional" in `docs/API_INVENTORY.md` is derived from path shape rather than from the spec. What was actually
  * measured against codeberg.org, and recorded in `golden/MANIFEST.md`:
  *
  *   - [[get]], [[list]] and [[repositories]] answer `200` anonymously — `golden/organization/org-single.json`,
  *     `org-list.json` and `org-repos-list.json` are those captures.
  *   - [[members]], [[teams]] and [[userOrganizations]] answer `401 token is required` anonymously, even though the
  *     inventory marks all three "optional". The bodies are `golden/error/401-org-teams.json` and
  *     `golden/error/401-user-orgs.json`.
  *   - [[publicMembers]], [[getTeam]], [[teamMembers]] and [[teamRepositories]] were not probed. The three team
  *     operations describe membership of a private structure, so expect them to behave like [[teams]].
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

/** The requests this group issues, its operation ids, and its typed rail. */
object OrganizationApi:

  /** The stable operation id [[OrganizationApi.get]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val GetOperation: String = "orgs.get"

  /** The stable operation id of [[OrganizationApi.list]]. */
  val ListOperation: String = "orgs.list"

  /** The stable operation id of [[OrganizationApi.repositories]]. */
  val RepositoriesOperation: String = "orgs.repos.list"

  /** The stable operation id of [[OrganizationApi.members]]. */
  val MembersOperation: String = "orgs.members.list"

  /** The stable operation id of [[OrganizationApi.publicMembers]]. */
  val PublicMembersOperation: String = "orgs.publicMembers.list"

  /** The stable operation id of [[OrganizationApi.teams]]. */
  val TeamsOperation: String = "orgs.teams.list"

  /** The stable operation id of [[OrganizationApi.getTeam]]. */
  val GetTeamOperation: String = "orgs.teams.get"

  /** The stable operation id of [[OrganizationApi.teamMembers]]. */
  val TeamMembersOperation: String = "orgs.teams.members.list"

  /** The stable operation id of [[OrganizationApi.teamRepositories]]. */
  val TeamRepositoriesOperation: String = "orgs.teams.repos.list"

  /** The stable operation id of [[OrganizationApi.userOrganizations]]. */
  val UserOrganizationsOperation: String = "orgs.userOrgs.list"

  /** The collection segment `/orgs`, which is both a path of its own and the prefix of every organisation path. */
  private val OrgsSegment: String = "orgs"

  /** The typed rail of [[OrganizationApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.organizations.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
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

    /** [[OrganizationApi.repositories]] with its failure as a value. */
    def repositories(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.repositories(org, params))

    /** [[OrganizationApi.members]] with its failure as a value. */
    def members(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.members(org, params))

    /** [[OrganizationApi.publicMembers]] with its failure as a value. */
    def publicMembers(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.publicMembers(org, params))

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

    /** [[OrganizationApi.userOrganizations]] with its failure as a value. */
    def userOrganizations(
        username: Username,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Organization]]] =
      exec.attempt(rail.userOrganizations(username, params))

  private def getRequest(org: OrgName): CodebergRequest =
    read(GetOperation, orgPath(org), Nil)

  private def listRequest(params: PageParams): CodebergRequest =
    read(ListOperation, List(OrgsSegment), window(params))

  private def repositoriesRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(RepositoriesOperation, orgPath(org) :+ "repos", window(params))

  private def membersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(MembersOperation, orgPath(org) :+ "members", window(params))

  private def publicMembersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(PublicMembersOperation, orgPath(org) :+ "public_members", window(params))

  private def teamsRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(TeamsOperation, orgPath(org) :+ "teams", window(params))

  private def getTeamRequest(id: TeamId): CodebergRequest =
    read(GetTeamOperation, teamPath(id), Nil)

  private def teamMembersRequest(id: TeamId, params: PageParams): CodebergRequest =
    read(TeamMembersOperation, teamPath(id) :+ "members", window(params))

  private def teamRepositoriesRequest(id: TeamId, params: PageParams): CodebergRequest =
    read(TeamRepositoriesOperation, teamPath(id) :+ "repos", window(params))

  private def userOrganizationsRequest(username: Username, params: PageParams): CodebergRequest =
    read(UserOrganizationsOperation, List("users", username.value, OrgsSegment), window(params))

  private def orgPath(org: OrgName): List[String] =
    List(OrgsSegment, org.value)

  /** Rooted at `/teams`, not below the organisation — see [[OrganizationApi.getTeam]]. */
  private def teamPath(id: TeamId): List[String] =
    List("teams", id.value.toString)

  /** Every operation in this group is a `GET` that carries no body and adds no header of its own. */
  private def read(operation: String, path: List[String], query: List[(String, String)]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** The `page` and `limit` parameters, in the order Forgejo's own `Link` header writes them. */
  private def window(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)
