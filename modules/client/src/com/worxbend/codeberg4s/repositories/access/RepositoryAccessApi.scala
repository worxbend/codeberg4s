package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.access.wire.{
  AccessQueries,
  AddCollaboratorOptionDto,
  CreateBranchProtectionOptionDto,
  CreateKeyOptionDto,
  EditBranchProtectionOptionDto,
  TagProtectionOptionDto
}
import com.worxbend.codeberg4s.users.{User, Username}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** A repository's access control surface: who may push, who may merge, which branches and tags are protected, which
  * machines hold a key, and which teams and accounts are collaborators.
  *
  * Reached as `client.repos.access`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryAccessApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This group decides who can do what, which changes what a bug costs==
  *
  * Everywhere else in this library a wrong field name loses a caller some data. Here it loses them a guarantee: a
  * branch protection rule created with one misspelled property is accepted with a `201`, appears in a listing, and does
  * not stop the push it was created to stop. That is why every wire spelling in this group lives in exactly one
  * constant — [[com.worxbend.codeberg4s.repositories.access.wire.BranchProtectionWire]],
  * [[com.worxbend.codeberg4s.repositories.access.wire.TagProtectionWire]],
  * [[com.worxbend.codeberg4s.repositories.access.wire.DeployKeyWire]] and
  * [[com.worxbend.codeberg4s.repositories.access.wire.CollaboratorWire]] — and why the suite asserts each of them
  * against the spec's own property name one at a time rather than round-tripping a body through a parser.
  *
  * ==Evidence==
  *
  * '''Every model in this group is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no fixture
  * exists for any of them — `golden/error/401-org-teams.json`, the `401` that probe collected, is the only artefact.
  * The field sets and the status vocabulary are the spec read literally; the nullability treatment is the conservative
  * one `docs/HAZARDS.md` §1 mandates for the whole API. Where a shape is asserted in a test, the payload was written by
  * hand to match that definition — it is not evidence that Forgejo sends exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope. Every endpoint in this group
  *     is token-only in practice even where `spec/swagger.v1.json` marks security as optional: reading a repository's
  *     collaborators or deploy keys is administrative, not public. `422` '''and''' `400` both mean the request was
  *     rejected as invalid; `docs/HAZARDS.md` §4 records Forgejo using `400` where a reader would expect `422`. Two
  *     statuses are specific to this group and appear nowhere else in the library: `423` on every protection-rule
  *     write, which is what an archived repository answers, and `405` on every team endpoint, which is what a
  *     repository owned by a user rather than by an organisation answers.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Writes are decided per endpoint against
  * one bar — a call may be repeated only when it names an identifier the instance never reuses, so that after `N`
  * attempts the end state is what one attempt would have produced and nothing has been created. Each decision is
  * justified where it is made; in summary:
  *
  *   - every `POST` uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]. Forgejo offers no idempotency key, so
  *     a repeated create produces a second rule or a second key;
  *   - every `PATCH` uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]] as well. A `PATCH` is not safe in the
  *     RFC 9110 sense, and this library does not decide on a caller's behalf that a partial update to an access rule
  *     may be replayed;
  *   - `DELETE` '''by numeric id''' — [[deleteTagProtection]], [[deleteDeployKey]] — uses
  *     [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]: the instance never reuses those numbers, so a
  *     repeat can only address the thing the first attempt addressed;
  *   - `DELETE` '''by name''' is decided one endpoint at a time and comes out both ways. [[deleteCollaborator]] is
  *     retried, [[deleteBranchProtection]] and [[deleteTeam]] are not. See each for why;
  *   - `PUT` is likewise split: [[addCollaborator]] is retried because it states a level, [[addTeam]] is not because it
  *     asserts an absence.
  *
  * One consequence applies to every retried delete and is stated here once: if the first attempt succeeded and its
  * response was lost, the retry addresses something that no longer exists and answers `404`. A `404` from a delete
  * therefore means "it is gone", not necessarily "it was never there".
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryAccessApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryAccessApi.Attempt = RepositoryAccessApi.Attempt(this)

  // --- branch protections ---------------------------------------------------

  /** Lists a repository's branch protection rules — `GET /repos/{owner}/{repo}/branch_protections`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so every rule arrives at once and the result is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] —
    * a page reporting a window nobody chose would be a lie about what was requested. A repository's rule count is
    * bounded by what an administrator typed, so this is not the unbounded read that would make paging necessary.
    *
    * '''This is the only way to see a rule whose name contains a `/`.''' Those cannot be addressed one at a time; see
    * [[BranchRuleName]].
    *
    * '''Failures.''' The group contract above.
    */
  def listBranchProtections(owner: Owner, name: RepoName): Future[Vector[BranchProtection]] =
    pipeline.call(RepositoryAccessApi.listBranchProtectionsRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.branchProtections)

  /** Reads one branch protection rule — `GET /repos/{owner}/{repo}/branch_protections/{name}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such rule in this repository" and "no such
    * repository", and — because the route matches one path segment — is also what a rule whose name contains a `/`
    * would answer if this library let such a name through. It does not; see [[BranchRuleName]].
    *
    * @param rule
    *   the rule's own name, which is a glob and not a branch
    */
  def branchProtection(owner: Owner, name: RepoName, rule: BranchRuleName): Future[BranchProtection] =
    pipeline.call(RepositoryAccessApi.branchProtectionRequest(owner, name, rule), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.branchProtection)

  /** Creates a branch protection rule — `POST /repos/{owner}/{repo}/branch_protections`.
    *
    * '''Never retried''', because it is a `POST` and this library repeats none. A repeat would answer `422` rather than
    * create a second rule — Forgejo rejects a duplicate rule name — but that is the instance's behaviour to change, not
    * a promise this library makes on its behalf. A transport failure therefore leaves the caller genuinely unsure
    * whether the rule exists, which [[listBranchProtections]] resolves.
    *
    * '''Only what the command states is sent.''' Everything the caller left unset takes Forgejo's own default; see
    * [[BranchProtectionSettings]].
    *
    * '''Failures.''' The group contract above. `423` is what an archived repository answers — a protection rule cannot
    * be added to something that is already frozen.
    */
  def createBranchProtection(
      owner: Owner,
      name: RepoName,
      command: CreateBranchProtection,
  ): Future[BranchProtection] =
    pipeline.call(RepositoryAccessApi.createBranchProtectionRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.branchProtection)

  /** Changes a branch protection rule — `PATCH /repos/{owner}/{repo}/branch_protections/{name}`.
    *
    * '''Never retried.''' A `PATCH` is not safe in the RFC 9110 sense, and this is the endpoint where replaying a
    * request the caller did not intend to replay changes who may push to a branch. Repeating this particular body would
    * in fact be state-identical — it states values rather than deltas — but that is a property of Forgejo's
    * implementation rather than of the method, and the library does not bet a branch on it. A caller who knows their
    * edit is safe to repeat can re-issue it.
    *
    * '''A rule cannot be renamed here.''' `EditBranchProtectionOption` declares neither `rule_name` nor `branch_name`,
    * so the name in the path is the only one there is.
    *
    * '''Anything the command leaves unset is left alone''' — see [[EditBranchProtection]] for why that is the single
    * most important sentence in this group.
    *
    * '''Failures.''' The group contract above, `423` included.
    */
  def editBranchProtection(
      owner: Owner,
      name: RepoName,
      rule: BranchRuleName,
      command: EditBranchProtection,
  ): Future[BranchProtection] =
    pipeline.call(
      RepositoryAccessApi.editBranchProtectionRequest(owner, name, rule, command),
      RetryEligibility.Never,
    )(using RepositoryAccessDecoders.branchProtection)

  /** Removes a branch protection rule — `DELETE /repos/{owner}/{repo}/branch_protections/{name}`.
    *
    * '''Never retried, unlike every other delete in this library.''' The bar for repeating a delete is that the request
    * names an identifier the instance never reuses, and a rule '''name''' is not one: a rule called `main` can be
    * deleted and another created under the same name a second later. If the first attempt succeeded and its response
    * was lost, a retry would delete whatever now answers to that name — which on this endpoint means silently
    * unprotecting a branch someone had just protected. [[deleteTagProtection]] is addressed by a number and is retried;
    * the difference between the two is exactly that.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteBranchProtection(owner: Owner, name: RepoName, rule: BranchRuleName): Future[Unit] =
    pipeline.callUnit(RepositoryAccessApi.deleteBranchProtectionRequest(owner, name, rule), RetryEligibility.Never)

  // --- tag protections ------------------------------------------------------

  /** Lists a repository's tag protection rules — `GET /repos/{owner}/{repo}/tag_protections`.
    *
    * '''Not paged''', for the reason [[listBranchProtections]] gives: the spec declares no `page` or `limit`.
    *
    * '''Failures.''' The group contract above. This operation declares no failure status at all in the spec, which is a
    * gap in the spec rather than a promise — `401` and `404` both occur.
    */
  def listTagProtections(owner: Owner, name: RepoName): Future[Vector[TagProtection]] =
    pipeline.call(RepositoryAccessApi.listTagProtectionsRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.tagProtections)

  /** Reads one tag protection rule — `GET /repos/{owner}/{repo}/tag_protections/{id}`.
    *
    * '''Addressed by a number, not by its pattern''' — unlike a branch rule. See [[TagProtectionId]].
    *
    * '''Failures.''' The group contract above.
    */
  def tagProtection(owner: Owner, name: RepoName, id: TagProtectionId): Future[TagProtection] =
    pipeline.call(RepositoryAccessApi.tagProtectionRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.tagProtection)

  /** Creates a tag protection rule — `POST /repos/{owner}/{repo}/tag_protections`.
    *
    * '''Never retried''', for the reason [[createBranchProtection]] gives.
    *
    * '''All three properties are sent''', whitelists included, because an omitted whitelist and an empty one mean the
    * same thing on a create; see [[com.worxbend.codeberg4s.repositories.access.wire.TagProtectionOptionDto]].
    *
    * '''Failures.''' The group contract above, `423` included.
    */
  def createTagProtection(owner: Owner, name: RepoName, command: CreateTagProtection): Future[TagProtection] =
    pipeline.call(RepositoryAccessApi.createTagProtectionRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.tagProtection)

  /** Changes a tag protection rule — `PATCH /repos/{owner}/{repo}/tag_protections/{id}`.
    *
    * '''Never retried''', for the reason [[editBranchProtection]] gives.
    *
    * '''Anything the command leaves unset is left alone''', and an explicitly empty whitelist clears it; see
    * [[EditTagProtection]].
    *
    * '''Failures.''' The group contract above, `423` included.
    */
  def editTagProtection(
      owner: Owner,
      name: RepoName,
      id: TagProtectionId,
      command: EditTagProtection,
  ): Future[TagProtection] =
    pipeline.call(RepositoryAccessApi.editTagProtectionRequest(owner, name, id, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.tagProtection)

  /** Removes a tag protection rule — `DELETE /repos/{owner}/{repo}/tag_protections/{id}`.
    *
    * '''Retried''', because the request names a number the instance never reuses: a repeat can only ever address the
    * rule the first attempt addressed, so `N` attempts leave the instance where one would have. That is precisely the
    * property [[deleteBranchProtection]] lacks, which is why that one is not retried. The cost is stated in the class
    * note — a retry after a lost success answers `404`.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteTagProtection(owner: Owner, name: RepoName, id: TagProtectionId): Future[Unit] =
    pipeline.callUnit(RepositoryAccessApi.deleteTagProtectionRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  // --- collaborators --------------------------------------------------------

  /** Lists a repository's collaborators — `GET /repos/{owner}/{repo}/collaborators`.
    *
    * '''Users, not grants.''' The endpoint answers `UserList`, so what comes back describes the accounts and says
    * nothing about what each may do. [[collaboratorAccess]] is how the level of one of them is read.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above.
    */
  def listCollaborators(owner: Owner, name: RepoName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(RepositoryAccessApi.listCollaboratorsRequest(owner, name, params), params)(using
      RepositoryAccessDecoders.collaborators)

  /** Asks whether an account is a collaborator — `GET /repos/{owner}/{repo}/collaborators/{collaborator}`.
    *
    * '''Success means yes; there is no `false`.''' The endpoint answers `204` with no body when the account is a
    * collaborator and `404` when it is not — and `404` is also what a repository the caller cannot see answers, and
    * what a repository that does not exist answers. Forgejo gives no way to tell those three apart, so this returns
    * `Unit` rather than a `Boolean` that would have to invent a distinction the API does not make. A caller who wants a
    * yes/no reads the typed rail and treats `Left(Api(_, 404, _))` as "not a collaborator, or not visible" — one
    * outcome, honestly named.
    *
    * '''Failures.''' The group contract above. `422` is what an unresolvable username produces, which is distinct from
    * `404` and is worth branching on: it means the account does not exist at all rather than that it is not a
    * collaborator.
    */
  def checkCollaborator(owner: Owner, name: RepoName, collaborator: Username): Future[Unit] =
    pipeline.callUnit(
      RepositoryAccessApi.checkCollaboratorRequest(owner, name, collaborator),
      RetryEligibility.IdempotentOnly,
    )

  /** Adds an account as a collaborator, or changes the level of one already there —
    * `PUT /repos/{owner}/{repo}/collaborators/{collaborator}`.
    *
    * '''One method for both, because the API has one endpoint for both.''' Forgejo answers `204` either way, with no
    * body, so there is nothing to return and nothing to tell the two apart with — [[collaboratorAccess]] before the
    * call is the only way to know which one will happen.
    *
    * '''Retried''', because the call sets a named account's grant to a stated level: it asserts a state rather than a
    * transition, so repeating it leaves the repository exactly as one attempt would, and nothing is created — the
    * account already exists, or the call fails. That is the same argument
    * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.setSecret]] makes, and it is why this `PUT` is
    * retried where [[addTeam]] is not.
    *
    * '''The level is always sent.''' [[CollaboratorPermission]] has exactly the three values the spec's `enum`
    * declares, and the renderer never omits the property, so Forgejo is never left to choose a level on this
    * repository's behalf.
    *
    * '''Failures.''' The group contract above. `403` is what adding a collaborator to a repository the token may read
    * but not administer produces, and `422` what an unresolvable username produces.
    */
  def addCollaborator(
      owner: Owner,
      name: RepoName,
      collaborator: Username,
      permission: CollaboratorPermission,
  ): Future[Unit] =
    pipeline.callUnit(
      RepositoryAccessApi.addCollaboratorRequest(owner, name, collaborator, permission),
      RetryEligibility.AlwaysRetry,
    )

  /** Removes a collaborator — `DELETE /repos/{owner}/{repo}/collaborators/{collaborator}`.
    *
    * '''Retried.''' A username addresses one account instance-wide, and removing that account's grant is state-valued:
    * after `N` attempts the repository has no grant for it, which is what one attempt would have produced, and nothing
    * is created. That is a weaker guarantee than [[deleteTagProtection]]'s — an account can in principle be deleted and
    * its handle re-registered — but the operation is a revocation, and revoking twice cannot grant anything. The cost
    * is stated in the class note.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `422` is what an unresolvable username produces.
    */
  def deleteCollaborator(owner: Owner, name: RepoName, collaborator: Username): Future[Unit] =
    pipeline.callUnit(
      RepositoryAccessApi.deleteCollaboratorRequest(owner, name, collaborator),
      RetryEligibility.AlwaysRetry,
    )

  /** Reads what one account may do with the repository —
    * `GET /repos/{owner}/{repo}/collaborators/{collaborator}/permission`.
    *
    * '''The level is reported both parsed and verbatim.''' See [[CollaboratorAccess]] for why this is the one place in
    * the library that does not simply drop an enum value it does not recognise.
    *
    * '''This answers for any account, not only for collaborators.''' A repository's owner, and an organisation member
    * who reaches it through a team, both have a permission here without appearing in [[listCollaborators]].
    *
    * '''Failures.''' The group contract above.
    */
  def collaboratorAccess(owner: Owner, name: RepoName, collaborator: Username): Future[CollaboratorAccess] =
    pipeline.call(
      RepositoryAccessApi.collaboratorAccessRequest(owner, name, collaborator),
      RetryEligibility.IdempotentOnly,
    )(using RepositoryAccessDecoders.collaboratorAccess)

  // --- deploy keys ----------------------------------------------------------

  /** Lists a repository's deploy keys — `GET /repos/{owner}/{repo}/keys`.
    *
    * '''Key material included, and it is not a secret.''' Every entry carries its public key in full; see [[DeployKey]]
    * for why that needs no redacting and why the sensitive part of a deploy key is [[DeployKey.isReadOnly]] rather than
    * [[DeployKey.key]].
    *
    * '''Paging.''' As [[listCollaborators]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    *
    * @param query
    *   the `key_id` and `fingerprint` filters; [[DeployKeyQuery.Empty]] asks for all of them
    */
  def listDeployKeys(
      owner: Owner,
      name: RepoName,
      query: DeployKeyQuery,
      params: PageParams,
  ): Future[Page[DeployKey]] =
    pipeline.callPage(RepositoryAccessApi.listDeployKeysRequest(owner, name, query, params), params)(using
      RepositoryAccessDecoders.deployKeys)

  /** Reads one deploy key — `GET /repos/{owner}/{repo}/keys/{id}`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param id
    *   the grant's own identifier, '''not''' [[DeployKey.keyId]] — see [[DeployKeyId]]
    */
  def deployKey(owner: Owner, name: RepoName, id: DeployKeyId): Future[DeployKey] =
    pipeline.call(RepositoryAccessApi.deployKeyRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.deployKey)

  /** Registers a deploy key — `POST /repos/{owner}/{repo}/keys`.
    *
    * '''Never retried''', because it is a `POST` and this library repeats none. A repeat would answer `422` — Forgejo
    * refuses a key already registered on the repository and refuses a duplicate title — rather than create a second
    * grant, but that is the instance's behaviour to change, not a promise this library makes on its behalf. A transport
    * failure therefore leaves the caller unsure whether the key exists, which [[listDeployKeys]] resolves.
    *
    * '''The grant is always stated.''' The rendered body always carries `read_only`, so a key never receives push
    * access because a property was omitted; see
    * [[com.worxbend.codeberg4s.repositories.access.wire.CreateKeyOptionDto]].
    *
    * '''Failures.''' The group contract above. `422` is what a malformed key, a duplicate key and a duplicate title all
    * produce.
    */
  def createDeployKey(owner: Owner, name: RepoName, command: CreateDeployKey): Future[DeployKey] =
    pipeline.call(RepositoryAccessApi.createDeployKeyRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.deployKey)

  /** Removes a deploy key — `DELETE /repos/{owner}/{repo}/keys/{id}`.
    *
    * '''Retried''', for the reason [[deleteTagProtection]] gives: the request names a number the instance never reuses.
    * The cost is stated in the class note — a retry after a lost success answers `404`.
    *
    * '''Answers `204`.''' The machine holding the private half loses access as soon as the instance has processed this;
    * there is no grace period and no confirmation beyond the status.
    *
    * '''Failures.''' The group contract above. `403` is what removing a key from a repository the token may read but
    * not administer produces.
    */
  def deleteDeployKey(owner: Owner, name: RepoName, id: DeployKeyId): Future[Unit] =
    pipeline.callUnit(RepositoryAccessApi.deleteDeployKeyRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  // --- teams ----------------------------------------------------------------

  /** Lists the teams with access to a repository — `GET /repos/{owner}/{repo}/teams`.
    *
    * '''Not paged''', for the reason [[listBranchProtections]] gives: the spec's own response is
    * `TeamListWithoutPagination` and the operation declares no `page` or `limit`.
    *
    * '''Only meaningful for a repository an organisation owns.''' A repository owned by a user has no teams, and
    * Forgejo answers `405` rather than an empty list — a distinct status this group is the only one to see.
    *
    * '''The model is borrowed.''' [[com.worxbend.codeberg4s.organizations.Team]] is owned by the organisations group
    * per `docs/LEDGER.md` and reused here rather than forked.
    *
    * '''Failures.''' The group contract above, `405` included.
    */
  def listTeams(owner: Owner, name: RepoName): Future[Vector[Team]] =
    pipeline.call(RepositoryAccessApi.listTeamsRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.teams)

  /** Asks whether a team has access to a repository — `GET /repos/{owner}/{repo}/teams/{team}`.
    *
    * '''This one answers with a body''', unlike its collaborator counterpart: a team that has access comes back as a
    * whole [[com.worxbend.codeberg4s.organizations.Team]], and a team that does not is a `404`. That asymmetry is the
    * API's, not this library's — see [[checkCollaborator]], which has nothing to return.
    *
    * '''Failures.''' The group contract above, `405` included.
    */
  def checkTeam(owner: Owner, name: RepoName, team: TeamName): Future[Team] =
    pipeline.call(RepositoryAccessApi.checkTeamRequest(owner, name, team), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.team)

  /** Grants a team access to the repository — `PUT /repos/{owner}/{repo}/teams/{team}`.
    *
    * '''Never retried, unlike [[addCollaborator]], and the asymmetry is deliberate.''' Two things separate them. First,
    * the bar for repeating a write is that the request names an identifier the instance never reuses, and a team
    * '''name''' is not one — [[com.worxbend.codeberg4s.organizations.TeamId]] records that names are unique only within
    * an organisation and that only the id distinguishes teams. Second, this endpoint carries no body: it asserts "this
    * team is not yet on the repository, put it there", where [[addCollaborator]] states a level. The spec declares
    * `422` for it, and the natural reading of a `422` here is that the grant already exists — so a retry after a lost
    * success would report a validation failure describing exactly the state the caller asked for, turning a success
    * into a reported error. A caller who knows their instance tolerates the repeat can re-issue the call.
    *
    * '''No body is sent''', because `AddCollaboratorOption` has no counterpart for teams: a team's own permission is
    * organisation-level configuration, and this endpoint only decides whether the repository is in the team's reach.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `405` is what a repository owned by a user answers, and `422` what a
    * team name that does not resolve in the owning organisation answers.
    */
  def addTeam(owner: Owner, name: RepoName, team: TeamName): Future[Unit] =
    pipeline.callUnit(RepositoryAccessApi.addTeamRequest(owner, name, team), RetryEligibility.Never)

  /** Withdraws a team's access to the repository — `DELETE /repos/{owner}/{repo}/teams/{team}`.
    *
    * '''Never retried''', for the two reasons [[addTeam]] gives, which apply unchanged: the team name is not an
    * identifier the instance never reuses, and the spec's `422` on this operation reads as "the team was not on the
    * repository", so a repeat after a lost success reports a failure for the state the caller wanted. This is the one
    * delete in the group that is neither by-id nor a revocation of a per-account grant, and it is not retried.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above, `405` included.
    */
  def deleteTeam(owner: Owner, name: RepoName, team: TeamName): Future[Unit] =
    pipeline.callUnit(RepositoryAccessApi.deleteTeamRequest(owner, name, team), RetryEligibility.Never)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryAccessApi:

  /** The stable operation id of [[RepositoryAccessApi.listBranchProtections]]. Safe to alert on. */
  val ListBranchProtectionsOperation: String = "repos.branchProtections.list"

  /** The stable operation id of the single-rule branch protection read on [[RepositoryAccessApi]]. */
  val GetBranchProtectionOperation: String = "repos.branchProtections.get"

  /** The stable operation id of [[RepositoryAccessApi.createBranchProtection]]. */
  val CreateBranchProtectionOperation: String = "repos.branchProtections.create"

  /** The stable operation id of [[RepositoryAccessApi.editBranchProtection]]. */
  val EditBranchProtectionOperation: String = "repos.branchProtections.edit"

  /** The stable operation id of [[RepositoryAccessApi.deleteBranchProtection]]. */
  val DeleteBranchProtectionOperation: String = "repos.branchProtections.delete"

  /** The stable operation id of [[RepositoryAccessApi.listTagProtections]]. */
  val ListTagProtectionsOperation: String = "repos.tagProtections.list"

  /** The stable operation id of the single-rule tag protection read on [[RepositoryAccessApi]]. */
  val GetTagProtectionOperation: String = "repos.tagProtections.get"

  /** The stable operation id of [[RepositoryAccessApi.createTagProtection]]. */
  val CreateTagProtectionOperation: String = "repos.tagProtections.create"

  /** The stable operation id of [[RepositoryAccessApi.editTagProtection]]. */
  val EditTagProtectionOperation: String = "repos.tagProtections.edit"

  /** The stable operation id of [[RepositoryAccessApi.deleteTagProtection]]. */
  val DeleteTagProtectionOperation: String = "repos.tagProtections.delete"

  /** The stable operation id of [[RepositoryAccessApi.listCollaborators]]. */
  val ListCollaboratorsOperation: String = "repos.collaborators.list"

  /** The stable operation id of [[RepositoryAccessApi.checkCollaborator]]. */
  val CheckCollaboratorOperation: String = "repos.collaborators.check"

  /** The stable operation id of [[RepositoryAccessApi.addCollaborator]]. */
  val AddCollaboratorOperation: String = "repos.collaborators.add"

  /** The stable operation id of [[RepositoryAccessApi.deleteCollaborator]]. */
  val DeleteCollaboratorOperation: String = "repos.collaborators.delete"

  /** The stable operation id of [[RepositoryAccessApi.collaboratorAccess]]. */
  val CollaboratorPermissionOperation: String = "repos.collaborators.permission"

  /** The stable operation id of [[RepositoryAccessApi.listDeployKeys]]. */
  val ListDeployKeysOperation: String = "repos.keys.list"

  /** The stable operation id of the single-key read on [[RepositoryAccessApi]]. */
  val GetDeployKeyOperation: String = "repos.keys.get"

  /** The stable operation id of [[RepositoryAccessApi.createDeployKey]]. */
  val CreateDeployKeyOperation: String = "repos.keys.create"

  /** The stable operation id of [[RepositoryAccessApi.deleteDeployKey]]. */
  val DeleteDeployKeyOperation: String = "repos.keys.delete"

  /** The stable operation id of [[RepositoryAccessApi.listTeams]]. */
  val ListTeamsOperation: String = "repos.teams.list"

  /** The stable operation id of [[RepositoryAccessApi.checkTeam]]. */
  val CheckTeamOperation: String = "repos.teams.check"

  /** The stable operation id of [[RepositoryAccessApi.addTeam]]. */
  val AddTeamOperation: String = "repos.teams.add"

  /** The stable operation id of [[RepositoryAccessApi.deleteTeam]]. */
  val DeleteTeamOperation: String = "repos.teams.delete"

  /** The typed rail of [[RepositoryAccessApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.access.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    *
    * This rail matters more here than elsewhere: [[RepositoryAccessApi.checkCollaborator]] answers a question whose
    * negative answer '''is''' a failure status, so a caller asking "is this account a collaborator?" reads a `Left`
    * rather than catching an exception.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryAccessApi)(using exec: Exec[Future]):

    /** [[RepositoryAccessApi.listBranchProtections]] with its failure as a value. */
    def listBranchProtections(
        owner: Owner,
        name: RepoName,
    ): Future[Either[CodebergError, Vector[BranchProtection]]] =
      exec.attempt(rail.listBranchProtections(owner, name))

    /** The single-rule branch protection read on [[RepositoryAccessApi]], with its failure as a value. */
    def branchProtection(
        owner: Owner,
        name: RepoName,
        rule: BranchRuleName,
    ): Future[Either[CodebergError, BranchProtection]] =
      exec.attempt(rail.branchProtection(owner, name, rule))

    /** [[RepositoryAccessApi.createBranchProtection]] with its failure as a value. */
    def createBranchProtection(
        owner: Owner,
        name: RepoName,
        command: CreateBranchProtection,
    ): Future[Either[CodebergError, BranchProtection]] =
      exec.attempt(rail.createBranchProtection(owner, name, command))

    /** [[RepositoryAccessApi.editBranchProtection]] with its failure as a value. */
    def editBranchProtection(
        owner: Owner,
        name: RepoName,
        rule: BranchRuleName,
        command: EditBranchProtection,
    ): Future[Either[CodebergError, BranchProtection]] =
      exec.attempt(rail.editBranchProtection(owner, name, rule, command))

    /** [[RepositoryAccessApi.deleteBranchProtection]] with its failure as a value. */
    def deleteBranchProtection(
        owner: Owner,
        name: RepoName,
        rule: BranchRuleName,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteBranchProtection(owner, name, rule))

    /** [[RepositoryAccessApi.listTagProtections]] with its failure as a value. */
    def listTagProtections(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[TagProtection]]] =
      exec.attempt(rail.listTagProtections(owner, name))

    /** The single-rule tag protection read on [[RepositoryAccessApi]], with its failure as a value. */
    def tagProtection(
        owner: Owner,
        name: RepoName,
        id: TagProtectionId,
    ): Future[Either[CodebergError, TagProtection]] =
      exec.attempt(rail.tagProtection(owner, name, id))

    /** [[RepositoryAccessApi.createTagProtection]] with its failure as a value. */
    def createTagProtection(
        owner: Owner,
        name: RepoName,
        command: CreateTagProtection,
    ): Future[Either[CodebergError, TagProtection]] =
      exec.attempt(rail.createTagProtection(owner, name, command))

    /** [[RepositoryAccessApi.editTagProtection]] with its failure as a value. */
    def editTagProtection(
        owner: Owner,
        name: RepoName,
        id: TagProtectionId,
        command: EditTagProtection,
    ): Future[Either[CodebergError, TagProtection]] =
      exec.attempt(rail.editTagProtection(owner, name, id, command))

    /** [[RepositoryAccessApi.deleteTagProtection]] with its failure as a value. */
    def deleteTagProtection(
        owner: Owner,
        name: RepoName,
        id: TagProtectionId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteTagProtection(owner, name, id))

    /** [[RepositoryAccessApi.listCollaborators]] with its failure as a value. */
    def listCollaborators(
        owner: Owner,
        name: RepoName,
        params: PageParams,
    ): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.listCollaborators(owner, name, params))

    /** [[RepositoryAccessApi.checkCollaborator]] with its failure as a value.
      *
      * This is the rail to use for that operation: a `Left` carrying `404` is the endpoint's way of saying "no", and
      * catching an exception to learn it would be strictly worse.
      */
    def checkCollaborator(
        owner: Owner,
        name: RepoName,
        collaborator: Username,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.checkCollaborator(owner, name, collaborator))

    /** [[RepositoryAccessApi.addCollaborator]] with its failure as a value. */
    def addCollaborator(
        owner: Owner,
        name: RepoName,
        collaborator: Username,
        permission: CollaboratorPermission,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.addCollaborator(owner, name, collaborator, permission))

    /** [[RepositoryAccessApi.deleteCollaborator]] with its failure as a value. */
    def deleteCollaborator(
        owner: Owner,
        name: RepoName,
        collaborator: Username,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteCollaborator(owner, name, collaborator))

    /** [[RepositoryAccessApi.collaboratorAccess]] with its failure as a value. */
    def collaboratorAccess(
        owner: Owner,
        name: RepoName,
        collaborator: Username,
    ): Future[Either[CodebergError, CollaboratorAccess]] =
      exec.attempt(rail.collaboratorAccess(owner, name, collaborator))

    /** [[RepositoryAccessApi.listDeployKeys]] with its failure as a value. */
    def listDeployKeys(
        owner: Owner,
        name: RepoName,
        query: DeployKeyQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[DeployKey]]] =
      exec.attempt(rail.listDeployKeys(owner, name, query, params))

    /** The single-key read on [[RepositoryAccessApi]], with its failure as a value. */
    def deployKey(owner: Owner, name: RepoName, id: DeployKeyId): Future[Either[CodebergError, DeployKey]] =
      exec.attempt(rail.deployKey(owner, name, id))

    /** [[RepositoryAccessApi.createDeployKey]] with its failure as a value. */
    def createDeployKey(
        owner: Owner,
        name: RepoName,
        command: CreateDeployKey,
    ): Future[Either[CodebergError, DeployKey]] =
      exec.attempt(rail.createDeployKey(owner, name, command))

    /** [[RepositoryAccessApi.deleteDeployKey]] with its failure as a value. */
    def deleteDeployKey(owner: Owner, name: RepoName, id: DeployKeyId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteDeployKey(owner, name, id))

    /** [[RepositoryAccessApi.listTeams]] with its failure as a value. */
    def listTeams(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[Team]]] =
      exec.attempt(rail.listTeams(owner, name))

    /** [[RepositoryAccessApi.checkTeam]] with its failure as a value. */
    def checkTeam(owner: Owner, name: RepoName, team: TeamName): Future[Either[CodebergError, Team]] =
      exec.attempt(rail.checkTeam(owner, name, team))

    /** [[RepositoryAccessApi.addTeam]] with its failure as a value. */
    def addTeam(owner: Owner, name: RepoName, team: TeamName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.addTeam(owner, name, team))

    /** [[RepositoryAccessApi.deleteTeam]] with its failure as a value. */
    def deleteTeam(owner: Owner, name: RepoName, team: TeamName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteTeam(owner, name, team))

  private def listBranchProtectionsRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListBranchProtectionsOperation, branchProtectionsPath(owner, name), Nil)

  private def branchProtectionRequest(owner: Owner, name: RepoName, rule: BranchRuleName): CodebergRequest =
    read(GetBranchProtectionOperation, branchProtectionPath(owner, name, rule), Nil)

  private def createBranchProtectionRequest(
      owner: Owner,
      name: RepoName,
      command: CreateBranchProtection,
  ): CodebergRequest =
    write(
      CreateBranchProtectionOperation,
      HttpMethod.Post,
      branchProtectionsPath(owner, name),
      CreateBranchProtectionOptionDto.render(command),
    )

  private def editBranchProtectionRequest(
      owner: Owner,
      name: RepoName,
      rule: BranchRuleName,
      command: EditBranchProtection,
  ): CodebergRequest =
    write(
      EditBranchProtectionOperation,
      HttpMethod.Patch,
      branchProtectionPath(owner, name, rule),
      EditBranchProtectionOptionDto.render(command),
    )

  private def deleteBranchProtectionRequest(owner: Owner, name: RepoName, rule: BranchRuleName): CodebergRequest =
    remove(DeleteBranchProtectionOperation, branchProtectionPath(owner, name, rule))

  private def listTagProtectionsRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListTagProtectionsOperation, tagProtectionsPath(owner, name), Nil)

  private def tagProtectionRequest(owner: Owner, name: RepoName, id: TagProtectionId): CodebergRequest =
    read(GetTagProtectionOperation, tagProtectionPath(owner, name, id), Nil)

  private def createTagProtectionRequest(
      owner: Owner,
      name: RepoName,
      command: CreateTagProtection,
  ): CodebergRequest =
    write(
      CreateTagProtectionOperation,
      HttpMethod.Post,
      tagProtectionsPath(owner, name),
      TagProtectionOptionDto.renderCreate(command),
    )

  private def editTagProtectionRequest(
      owner: Owner,
      name: RepoName,
      id: TagProtectionId,
      command: EditTagProtection,
  ): CodebergRequest =
    write(
      EditTagProtectionOperation,
      HttpMethod.Patch,
      tagProtectionPath(owner, name, id),
      TagProtectionOptionDto.renderEdit(command),
    )

  private def deleteTagProtectionRequest(owner: Owner, name: RepoName, id: TagProtectionId): CodebergRequest =
    remove(DeleteTagProtectionOperation, tagProtectionPath(owner, name, id))

  private def listCollaboratorsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListCollaboratorsOperation, collaboratorsPath(owner, name), AccessQueries.paging(params))

  private def checkCollaboratorRequest(owner: Owner, name: RepoName, collaborator: Username): CodebergRequest =
    read(CheckCollaboratorOperation, collaboratorPath(owner, name, collaborator), Nil)

  private def addCollaboratorRequest(
      owner: Owner,
      name: RepoName,
      collaborator: Username,
      permission: CollaboratorPermission,
  ): CodebergRequest =
    write(
      AddCollaboratorOperation,
      HttpMethod.Put,
      collaboratorPath(owner, name, collaborator),
      AddCollaboratorOptionDto.render(permission),
    )

  private def deleteCollaboratorRequest(owner: Owner, name: RepoName, collaborator: Username): CodebergRequest =
    remove(DeleteCollaboratorOperation, collaboratorPath(owner, name, collaborator))

  private def collaboratorAccessRequest(owner: Owner, name: RepoName, collaborator: Username): CodebergRequest =
    read(CollaboratorPermissionOperation, collaboratorPath(owner, name, collaborator) :+ "permission", Nil)

  private def listDeployKeysRequest(
      owner: Owner,
      name: RepoName,
      query: DeployKeyQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListDeployKeysOperation,
      deployKeysPath(owner, name),
      AccessQueries.deployKeys(query) ++ AccessQueries.paging(params),
    )

  private def deployKeyRequest(owner: Owner, name: RepoName, id: DeployKeyId): CodebergRequest =
    read(GetDeployKeyOperation, deployKeyPath(owner, name, id), Nil)

  private def createDeployKeyRequest(owner: Owner, name: RepoName, command: CreateDeployKey): CodebergRequest =
    write(
      CreateDeployKeyOperation,
      HttpMethod.Post,
      deployKeysPath(owner, name),
      CreateKeyOptionDto.render(command),
    )

  private def deleteDeployKeyRequest(owner: Owner, name: RepoName, id: DeployKeyId): CodebergRequest =
    remove(DeleteDeployKeyOperation, deployKeyPath(owner, name, id))

  private def listTeamsRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListTeamsOperation, teamsPath(owner, name), Nil)

  private def checkTeamRequest(owner: Owner, name: RepoName, team: TeamName): CodebergRequest =
    read(CheckTeamOperation, teamPath(owner, name, team), Nil)

  /** The team grant, which carries no body at all; see [[RepositoryAccessApi.addTeam]]. */
  private def addTeamRequest(owner: Owner, name: RepoName, team: TeamName): CodebergRequest =
    bodiless(AddTeamOperation, HttpMethod.Put, teamPath(owner, name, team))

  private def deleteTeamRequest(owner: Owner, name: RepoName, team: TeamName): CodebergRequest =
    remove(DeleteTeamOperation, teamPath(owner, name, team))

  private def branchProtectionsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "branch_protections"

  private def branchProtectionPath(owner: Owner, name: RepoName, rule: BranchRuleName): List[String] =
    branchProtectionsPath(owner, name) :+ rule.value

  private def tagProtectionsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "tag_protections"

  private def tagProtectionPath(owner: Owner, name: RepoName, id: TagProtectionId): List[String] =
    tagProtectionsPath(owner, name) :+ id.value.toString

  private def collaboratorsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "collaborators"

  private def collaboratorPath(owner: Owner, name: RepoName, collaborator: Username): List[String] =
    collaboratorsPath(owner, name) :+ collaborator.value

  private def deployKeysPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "keys"

  private def deployKeyPath(owner: Owner, name: RepoName, id: DeployKeyId): List[String] =
    deployKeysPath(owner, name) :+ id.value.toString

  private def teamsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "teams"

  private def teamPath(owner: Owner, name: RepoName, team: TeamName): List[String] =
    teamsPath(owner, name) :+ team.value
