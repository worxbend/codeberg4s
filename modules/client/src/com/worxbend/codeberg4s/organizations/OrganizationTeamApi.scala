package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.organizations.wire.{OrganizationQueries, TeamOptionDto}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.admin.RepositoryActivity
import com.worxbend.codeberg4s.users.{User, Username}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, RepoName}

import scala.concurrent.Future

import java.time.LocalDate

/** Teams: creating them, changing them, and deciding who and what they reach.
  *
  * Reached as `client.organizations.teamAdmin` — the name `teams` is already the listing method on [[OrganizationApi]].
  * Listing an organisation's teams and reading one team, its members and its repositories live on [[OrganizationApi]],
  * where they were written; this class is everything that changes a team plus the four single-subject reads that pair
  * with a write.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[OrganizationTeamApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Two roots, and which one a call uses decides whether it may be retried==
  *
  * [[create]] and [[search]] hang off `/orgs/{org}/teams`, because a team is created inside an organisation and
  * searched for within one. Everything else is rooted at `/teams/{id}`: once a team exists it is addressed by its
  * instance-wide [[TeamId]] and never by its name, since two organisations may each own a team called `owners`.
  *
  * That is not a naming curiosity. An [[OrgName]] is a handle [[OrganizationApi.rename]] can move to a different
  * organisation, and a [[TeamId]] is a database row id Forgejo never reuses — which is exactly the distinction the
  * retry rules below turn on.
  *
  * ==Evidence==
  *
  * '''Nothing about teams is captured.''' `golden/MANIFEST.md` records `GET /orgs/{org}/teams` answering
  * `401 token is required` to an anonymous caller on codeberg.org, and `golden/error/401-org-teams.json` is the only
  * artefact of that probe. Every model and every request body in this class is `spec/swagger.v1.json` read literally
  * under the rule `docs/HAZARDS.md` §1 forces on the whole API; the payloads asserted in the suites were written by
  * hand to match those definitions. Should a capture ever contradict them, the capture wins.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than on each method.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with `404` when the organisation, the team, the account or the
  *     repository does not exist '''or''' is not visible to the configured credentials — Forgejo does not distinguish
  *     the two, on purpose. `401` without a token, which is the ordinary anonymous outcome for every route here, and
  *     `403` when the token lacks the standing; the team-repository writes answer `403` specifically when the caller
  *     may see the team but not administer it. `422` '''and''' `400` both mean the request was rejected as invalid, per
  *     `docs/HAZARDS.md` §4.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, at its position for a listing.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here.
  *
  * ==Retries, in one place==
  *
  * Reads are [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The writes divide three ways, and each
  * decision is argued where it is made:
  *
  *   - [[create]] is a `POST` and is never retried.
  *   - [[delete]], [[addMember]] and [[removeMember]] are
  *     [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]. Each names the team by an id Forgejo never reuses
  *     and states an end condition rather than an increment, so N attempts leave what one would and create nothing.
  *   - [[edit]], [[addRepository]] and [[removeRepository]] are
  *     [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]. The edit clobbers, and the two repository calls name
  *     their subject by `{org}/{repo}` — a pair either half of which can be renamed and re-taken.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class OrganizationTeamApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationTeamApi.Attempt = OrganizationTeamApi.Attempt(this)

  // --- the team itself ------------------------------------------------------

  /** Creates a team in an organisation — `POST /orgs/{org}/teams`.
    *
    * '''Never retried.''' A team name is not an identifier: Forgejo will happily hold two teams called `reviewers` in
    * one organisation, so a repeat after a lost success creates a '''second''' team with the same name, the same units
    * and the same access — and the members later added to one of them do not reach the other. The organisation's team
    * listing resolves the uncertainty a transport failure leaves; guessing on the caller's behalf would not.
    *
    * '''Answers `201` with the created team''', including the [[TeamId]] every other route here takes.
    *
    * '''Failures.''' The group contract above. A `422` is what Forgejo answers for a name it reserves — `owners` is one
    * — for a duplicate it declines, and for a `permission` outside the three
    * [[com.worxbend.codeberg4s.organizations.CreateTeam]] documents.
    *
    * @param org
    *   the organisation the team is created in
    * @param command
    *   the team to create
    */
  def create(org: OrgName, command: CreateTeam): Future[Team] =
    pipeline.call(OrganizationTeamApi.createRequest(org, command), RetryEligibility.Never)(using
      OrganizationDecoders.team)

  /** Searches an organisation's teams — `GET /orgs/{org}/teams/search`.
    *
    * '''This is the one endpoint in the whole organisation group with an envelope.''' The spec types its `200` as an
    * inline object carrying `data` and `ok` rather than as a bare array, which is the same `{"ok", "data"}` wrapper
    * `docs/HAZARDS.md` §3 measured on `/repos/search`. A caller never sees it — the decoder unwraps — but a decoding
    * failure inside an element reports `$.data[2].id` rather than `$[2].id`, and that is why.
    *
    * '''`ok` is carried, not asserted on.''' Nothing documents what a `false` would mean; see
    * [[com.worxbend.codeberg4s.wire.SearchEnvelopeDto]].
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation whose teams are searched
    * @param text
    *   what to match against the team name, absent to match everything
    * @param includeDescription
    *   whether descriptions are searched as well as names. Absent sends nothing and leaves the instance's default,
    *   which the spec does not state
    * @param params
    *   the page to fetch and how many teams it may hold
    */
  def search(
      org: OrgName,
      text: Option[String],
      includeDescription: Option[Boolean],
      params: PageParams,
  ): Future[Page[Team]] =
    pipeline.callPage(OrganizationTeamApi.searchRequest(org, text, includeDescription, params), params)(using
      OrganizationDecoders.teamSearchResults)

  /** Edits a team — `PATCH /teams/{id}`.
    *
    * '''Never retried''', and the contrast with [[delete]] is the point. The request does name the team by an id
    * Forgejo never reuses, which is half of what a retry needs — but `EditTeamOption` declares `name` '''required''',
    * so every edit asserts what the team is called. A repeat after a lost success therefore overwrites whatever the
    * team has become in the meantime, including somebody else's rename or permission change, and Forgejo offers no
    * conditional-update header that would let the instance refuse a stale write.
    * [[com.worxbend.codeberg4s.issues.IssueLabelApi.edit]] declines to retry for exactly this reason.
    *
    * '''Turning [[com.worxbend.codeberg4s.organizations.EditTeam.includesAllRepositories]] on is retroactive''': the
    * team gains every repository the organisation already has, in one call.
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param command
    *   what the team should be; see [[com.worxbend.codeberg4s.organizations.EditTeam]] for why it has no empty value
    */
  def edit(id: TeamId, command: EditTeam): Future[Team] =
    pipeline.call(OrganizationTeamApi.editRequest(id, command), RetryEligibility.Never)(using OrganizationDecoders.team)

  /** Deletes a team — `DELETE /teams/{id}`.
    *
    * '''Retried''', and this is the clean case the bar was written for: the request names exactly one object, by a
    * database row id the instance never reuses and never rebinds, so the state after N attempts is the state after one
    * and nothing is created. The `{id}` is what makes it safe — the same operation spelled by team '''name''' would not
    * be, because a name freed by a delete can be taken again.
    *
    * The cost a caller has to know is the usual one: if the first attempt succeeded and its response was lost, the
    * retry addresses something that no longer exists and answers `404`. A `404` from this call therefore means "it is
    * gone", not necessarily "it was never there".
    *
    * '''The repositories survive.''' Deleting a team removes the access it granted, not the repositories it reached.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    */
  def delete(id: TeamId): Future[Unit] =
    pipeline.callUnit(OrganizationTeamApi.deleteRequest(id), RetryEligibility.AlwaysRetry)

  /** Lists a team's activity feed — `GET /teams/{id}/activities/feeds`.
    *
    * The entries are [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity]] values — Forgejo's one
    * `Activity` model, which this group imports rather than forks; see [[OrganizationDecoders]]. The name reads oddly
    * on a team feed and is still the right type.
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param date
    *   the calendar day to report, absent for the instance's own default window. A day and not an instant: the spec
    *   declares `format: date`, so which day it is depends on the instance's clock
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def activities(id: TeamId, date: Option[LocalDate], params: PageParams): Future[Page[RepositoryActivity]] =
    pipeline.callPage(OrganizationTeamApi.activitiesRequest(id, date, params), params)(using
      OrganizationDecoders.activities)

  // --- members --------------------------------------------------------------

  /** Reads one member of a team — `GET /teams/{id}/members/{username}`.
    *
    * '''Answers the user object, not a status.''' Unlike [[OrganizationApi.isMember]], which Forgejo answers `204` or
    * `404`, this route returns a [[com.worxbend.codeberg4s.users.User]] body on `200`. A `404` therefore travels on the
    * error channel here, as it does everywhere else, and means "not a member of this team" as well as "no such team"
    * and "no such account" — three cases the response cannot tell apart.
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param username
    *   the account handle
    */
  def member(id: TeamId, username: Username): Future[User] =
    pipeline.call(OrganizationTeamApi.memberRequest(id, username), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.user)

  /** Adds an account to a team — `PUT /teams/{id}/members/{username}`.
    *
    * '''Retried.''' The request names the team by an id Forgejo never reuses and states an end condition — "this
    * account is in this team" — rather than an increment. Applying it twice leaves exactly the state applying it once
    * would, and nothing is created: a second attempt after a lost success finds the membership already there and
    * answers `204` again.
    *
    * The residual risk is the account handle, which Forgejo does let a user change, freeing the old one. It is a much
    * weaker exposure than the one that makes the repository writes below
    * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]: what this call grants is membership of a team whose reach
    * is already decided, it is visible in [[OrganizationApi.teamMembers]], and it is undone by one call to
    * [[removeMember]].
    *
    * '''Answers `204`''', and says nothing about whether the account was already a member.
    *
    * '''Failures.''' The group contract above. Forgejo answers `404` for an account that is not a member of the
    * '''organisation''' — a team may only contain people the organisation already holds.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param username
    *   the account to add
    */
  def addMember(id: TeamId, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationTeamApi.addMemberRequest(id, username), RetryEligibility.AlwaysRetry)

  /** Removes an account from a team — `DELETE /teams/{id}/members/{username}`.
    *
    * '''Retried''', on the same argument as [[addMember]] and with one difference: a removal cannot leave anything
    * behind, so a repeat after a lost success is a no-op that answers `204` rather than a `404` — Forgejo treats
    * "remove someone who is not in the team" as done rather than as missing.
    *
    * '''This does not remove them from the organisation'''; [[OrganizationApi.removeMember]] does that.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param username
    *   the account to remove
    */
  def removeMember(id: TeamId, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationTeamApi.removeMemberRequest(id, username), RetryEligibility.AlwaysRetry)

  // --- repositories ---------------------------------------------------------

  /** Reads one repository a team reaches — `GET /teams/{id}/repos/{org}/{repo}`.
    *
    * The `{org}` here is the '''repository's''' owner and need not be the organisation that owns the team; Forgejo
    * takes both because the pair is how a repository is named. A repository the team does not reach is a `404`, which
    * is also what a repository that does not exist produces.
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param org
    *   the repository's owner
    * @param name
    *   the repository's name
    */
  def repository(id: TeamId, org: OrgName, name: RepoName): Future[Repository] =
    pipeline.call(OrganizationTeamApi.repositoryRequest(id, org, name), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.repository)

  /** Gives a team access to a repository — `PUT /teams/{id}/repos/{org}/{repo}`.
    *
    * '''Never retried''', and the contrast with [[addMember]] is deliberate. The team is named by an id Forgejo never
    * reuses, but the '''subject''' is named by `{org}/{repo}` — a pair either half of which can be renamed and then
    * taken by somebody else. If the first attempt succeeded and its response was lost, a retry can therefore grant the
    * team access to whatever now answers to that name, which is a different repository's source code. That the window
    * is small does not make it acceptable: this call moves read access to content, which is the one thing a client
    * library must not gamble with on the caller's behalf.
    *
    * A caller who wants an at-least-once guarantee reads [[repository]] afterwards and repeats deliberately.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `403` is what Forgejo answers when the caller may see the team but may
    * not change what it reaches.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param org
    *   the repository's owner
    * @param name
    *   the repository's name
    */
  def addRepository(id: TeamId, org: OrgName, name: RepoName): Future[Unit] =
    pipeline.callUnit(OrganizationTeamApi.addRepositoryRequest(id, org, name), RetryEligibility.Never)

  /** Takes a repository away from a team — `DELETE /teams/{id}/repos/{org}/{repo}`.
    *
    * '''Never retried''', for the same reason as [[addRepository]]: the subject is named by a renameable pair, so a
    * repeat after a lost success can revoke a team's access to a repository that merely inherited the name. That
    * failure is quieter than the one [[addRepository]] risks — access is lost rather than granted — and it is still a
    * change to something nobody asked about, which is what
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] is meant to exclude.
    *
    * '''The repository is untouched.''' This removes the team's access, not the repository.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the team's instance-wide identifier
    * @param org
    *   the repository's owner
    * @param name
    *   the repository's name
    */
  def removeRepository(id: TeamId, org: OrgName, name: RepoName): Future[Unit] =
    pipeline.callUnit(OrganizationTeamApi.removeRepositoryRequest(id, org, name), RetryEligibility.Never)

/** The requests [[OrganizationTeamApi]] issues, its operation ids, and its typed rail. */
object OrganizationTeamApi:

  /** The stable operation id of [[OrganizationTeamApi.create]]. Safe to alert on. */
  val CreateOperation: String = "orgs.teams.create"

  /** The stable operation id of [[OrganizationTeamApi.search]]. */
  val SearchOperation: String = "orgs.teams.search"

  /** The stable operation id of [[OrganizationTeamApi.edit]]. */
  val EditOperation: String = "orgs.teams.edit"

  /** The stable operation id of [[OrganizationTeamApi.delete]]. Worth alerting on by itself: it revokes access. */
  val DeleteOperation: String = "orgs.teams.delete"

  /** The stable operation id of [[OrganizationTeamApi.activities]]. */
  val ActivitiesOperation: String = "orgs.teams.activities.list"

  /** The stable operation id of the single-member read on [[OrganizationTeamApi]]. */
  val MemberOperation: String = "orgs.teams.members.get"

  /** The stable operation id of [[OrganizationTeamApi.addMember]]. */
  val AddMemberOperation: String = "orgs.teams.members.add"

  /** The stable operation id of [[OrganizationTeamApi.removeMember]]. */
  val RemoveMemberOperation: String = "orgs.teams.members.remove"

  /** The stable operation id of the single-repository read on [[OrganizationTeamApi]]. */
  val RepositoryOperation: String = "orgs.teams.repos.get"

  /** The stable operation id of [[OrganizationTeamApi.addRepository]]. Worth alerting on: it grants access. */
  val AddRepositoryOperation: String = "orgs.teams.repos.add"

  /** The stable operation id of [[OrganizationTeamApi.removeRepository]]. */
  val RemoveRepositoryOperation: String = "orgs.teams.repos.remove"

  /** The path segment a team's member collection sits under. */
  private val MembersSegment: String = "members"

  /** The path segment a team's repository collection sits under. */
  private val ReposSegment: String = "repos"

  /** The typed rail of [[OrganizationTeamApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.organizations.teams.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationTeamApi)(using exec: Exec[Future]):

    /** [[OrganizationTeamApi.create]] with its failure as a value. */
    def create(org: OrgName, command: CreateTeam): Future[Either[CodebergError, Team]] =
      exec.attempt(rail.create(org, command))

    /** [[OrganizationTeamApi.search]] with its failure as a value. */
    def search(
        org: OrgName,
        text: Option[String],
        includeDescription: Option[Boolean],
        params: PageParams,
    ): Future[Either[CodebergError, Page[Team]]] =
      exec.attempt(rail.search(org, text, includeDescription, params))

    /** [[OrganizationTeamApi.edit]] with its failure as a value. */
    def edit(id: TeamId, command: EditTeam): Future[Either[CodebergError, Team]] =
      exec.attempt(rail.edit(id, command))

    /** [[OrganizationTeamApi.delete]] with its failure as a value. */
    def delete(id: TeamId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(id))

    /** [[OrganizationTeamApi.activities]] with its failure as a value. */
    def activities(
        id: TeamId,
        date: Option[LocalDate],
        params: PageParams,
    ): Future[Either[CodebergError, Page[RepositoryActivity]]] =
      exec.attempt(rail.activities(id, date, params))

    /** The single-member read on [[OrganizationTeamApi]], with its failure as a value. */
    def member(id: TeamId, username: Username): Future[Either[CodebergError, User]] =
      exec.attempt(rail.member(id, username))

    /** [[OrganizationTeamApi.addMember]] with its failure as a value. */
    def addMember(id: TeamId, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.addMember(id, username))

    /** [[OrganizationTeamApi.removeMember]] with its failure as a value. */
    def removeMember(id: TeamId, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeMember(id, username))

    /** The single-repository read on [[OrganizationTeamApi]], with its failure as a value. */
    def repository(id: TeamId, org: OrgName, name: RepoName): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.repository(id, org, name))

    /** [[OrganizationTeamApi.addRepository]] with its failure as a value. */
    def addRepository(id: TeamId, org: OrgName, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.addRepository(id, org, name))

    /** [[OrganizationTeamApi.removeRepository]] with its failure as a value. */
    def removeRepository(id: TeamId, org: OrgName, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeRepository(id, org, name))

  private def createRequest(org: OrgName, command: CreateTeam): CodebergRequest =
    write(
      CreateOperation,
      HttpMethod.Post,
      OrganizationRequests.organizationPath(org) :+ OrganizationRequests.TeamsSegment,
      TeamOptionDto.renderCreate(command),
    )

  private def searchRequest(
      org: OrgName,
      text: Option[String],
      includeDescription: Option[Boolean],
      params: PageParams,
  ): CodebergRequest =
    read(
      SearchOperation,
      OrganizationRequests.organizationPath(org) ++ List(OrganizationRequests.TeamsSegment, "search"),
      OrganizationQueries.teamSearch(text, includeDescription, params),
    )

  private def editRequest(id: TeamId, command: EditTeam): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      OrganizationRequests.teamPath(id),
      TeamOptionDto.renderEdit(command),
    )

  private def deleteRequest(id: TeamId): CodebergRequest =
    bodiless(DeleteOperation, HttpMethod.Delete, OrganizationRequests.teamPath(id))

  private def activitiesRequest(id: TeamId, date: Option[LocalDate], params: PageParams): CodebergRequest =
    read(
      ActivitiesOperation,
      OrganizationRequests.teamPath(id) ++ List("activities", "feeds"),
      OrganizationQueries.activities(date, params),
    )

  private def memberRequest(id: TeamId, username: Username): CodebergRequest =
    read(MemberOperation, memberPath(id, username), Nil)

  private def addMemberRequest(id: TeamId, username: Username): CodebergRequest =
    bodiless(AddMemberOperation, HttpMethod.Put, memberPath(id, username))

  private def removeMemberRequest(id: TeamId, username: Username): CodebergRequest =
    bodiless(RemoveMemberOperation, HttpMethod.Delete, memberPath(id, username))

  private def repositoryRequest(id: TeamId, org: OrgName, name: RepoName): CodebergRequest =
    read(RepositoryOperation, repositoryPath(id, org, name), Nil)

  private def addRepositoryRequest(id: TeamId, org: OrgName, name: RepoName): CodebergRequest =
    bodiless(AddRepositoryOperation, HttpMethod.Put, repositoryPath(id, org, name))

  private def removeRepositoryRequest(id: TeamId, org: OrgName, name: RepoName): CodebergRequest =
    bodiless(RemoveRepositoryOperation, HttpMethod.Delete, repositoryPath(id, org, name))

  private def memberPath(id: TeamId, username: Username): List[String] =
    OrganizationRequests.teamPath(id) ++ List(MembersSegment, username.value)

  private def repositoryPath(id: TeamId, org: OrgName, name: RepoName): List[String] =
    OrganizationRequests.teamPath(id) ++ List(ReposSegment, org.value, name.value)
