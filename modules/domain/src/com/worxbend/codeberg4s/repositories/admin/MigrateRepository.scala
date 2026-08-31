package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.organizations.TeamId
import com.worxbend.codeberg4s.{Owner, RepoName}

/** Everything `POST /repos/migrate` may be told, as one value.
  *
  * Derived from `MigrateRepoOptions` in `spec/swagger.v1.json`, which declares `clone_addr` and `repo_name` required
  * and eighteen optional properties. No golden capture of this request exists.
  *
  * ==This request carries a credential for somebody else's forge==
  *
  * [[credential]] is a [[RemoteCredential]] and it is the reason this type exists rather than a bag of strings: the
  * value is a GitHub token or a GitLab password, it travels in a request body, and it must never appear in a log line,
  * a [[com.worxbend.codeberg4s.CallContext]] or a [[com.worxbend.codeberg4s.CodebergError]]. `RemoteCredential`'s own
  * `toString` is the mask, so the `toString` this case class generates is masked too — a command may be logged whole
  * without leaking.
  *
  * ==Mirror or copy==
  *
  * Without [[asMirror]] Forgejo takes a one-time copy and the result is an ordinary repository. With it, the result is
  * a pull mirror that keeps re-fetching on [[mirrorInterval]] and refuses local pushes. Converting a mirror back to an
  * ordinary repository afterwards is `RepositoryAdminApi.convert`, and it is one-way.
  *
  * ==The content flags need a service that has the content==
  *
  * [[includesIssues]], [[includesLabels]], [[includesMilestones]], [[includesPullRequests]] and [[includesReleases]]
  * are only meaningful when [[service]] names a forge Forgejo can talk to over an API. With [[MigrationService.Git]] —
  * a bare Git remote — there is nothing but commits to fetch, and the flags are ignored.
  *
  * @param cloneAddress
  *   the URL to fetch from. Required by Forgejo
  * @param repoName
  *   what the new repository will be called. Required by Forgejo
  * @param repoOwner
  *   the user or organisation that will own it. Absent means the authenticated account
  * @param description
  *   the new repository's description
  * @param service
  *   which kind of forge is at [[cloneAddress]] — see [[MigrationService]]
  * @param username
  *   the account to authenticate to the remote as
  * @param credential
  *   the password or token for the remote, sent as `auth_password`. Write-only — see [[RemoteCredential]]
  * @param token
  *   the token for the remote, sent as `auth_token`. Forgejo accepts either this or [[credential]] depending on the
  *   service; both are write-only for the same reason
  * @param isPrivate
  *   whether the new repository is private
  * @param isMirror
  *   whether to keep fetching, rather than take one copy — see the note above
  * @param mirrorInterval
  *   how often the mirror re-fetches, in Go's duration spelling such as `8h0m0s`. Only with [[isMirror]]
  * @param lfs
  *   fetch Git LFS objects as well
  * @param lfsEndpoint
  *   where to fetch LFS objects from, when it is not the default for [[cloneAddress]]
  * @param includesIssues
  *   copy issues. See the note above
  * @param includesLabels
  *   copy labels
  * @param includesMilestones
  *   copy milestones
  * @param includesPullRequests
  *   copy pull requests
  * @param includesReleases
  *   copy releases
  * @param includesWiki
  *   copy the wiki
  */
final case class MigrateRepository(
    cloneAddress: String,
    repoName: RepoName,
    repoOwner: Option[Owner],
    description: Option[String],
    service: Option[MigrationService],
    username: Option[String],
    credential: Option[RemoteCredential],
    token: Option[RemoteCredential],
    isPrivate: Boolean,
    isMirror: Boolean,
    mirrorInterval: Option[String],
    lfs: Boolean,
    lfsEndpoint: Option[String],
    includesIssues: Boolean,
    includesLabels: Boolean,
    includesMilestones: Boolean,
    includesPullRequests: Boolean,
    includesReleases: Boolean,
    includesWiki: Boolean,
):

  /** Places the new repository under `owner` instead of the authenticated account. */
  def ownedBy(owner: Owner): MigrateRepository = copy(repoOwner = Some(owner))

  /** Describes the new repository. */
  def describedAs(text: String): MigrateRepository = copy(description = Some(text))

  /** States which kind of forge is being migrated from. */
  def usingService(kind: MigrationService): MigrateRepository = copy(service = Some(kind))

  /** Authenticates to the remote as `user` with `password`. The credential never leaves the request body. */
  def authenticatedAs(user: String, password: RemoteCredential): MigrateRepository =
    copy(username = Some(user), credential = Some(password))

  /** Authenticates to the remote with a token rather than a password. Write-only, as [[authenticatedAs]] is. */
  def authenticatedWith(remoteToken: RemoteCredential): MigrateRepository = copy(token = Some(remoteToken))

  /** Creates the new repository private. */
  def asPrivate: MigrateRepository = copy(isPrivate = true)

  /** Keeps the new repository fetching from the remote, rather than copying once. See the type note. */
  def asMirror: MigrateRepository = copy(isMirror = true)

  /** Sets how often the mirror re-fetches, in Go's duration spelling such as `8h0m0s`. */
  def every(duration: String): MigrateRepository = copy(mirrorInterval = Some(duration))

  /** Fetches Git LFS objects too. */
  def withLfs: MigrateRepository = copy(lfs = true)

  /** Fetches Git LFS objects from a specific endpoint. */
  def withLfsFrom(endpoint: String): MigrateRepository = copy(lfs = true, lfsEndpoint = Some(endpoint))

  /** Copies issues. Needs an API-capable [[service]] — see the type note. */
  def withIssues: MigrateRepository = copy(includesIssues = true)

  /** Copies labels. */
  def withLabels: MigrateRepository = copy(includesLabels = true)

  /** Copies milestones. */
  def withMilestones: MigrateRepository = copy(includesMilestones = true)

  /** Copies pull requests. */
  def withPullRequests: MigrateRepository = copy(includesPullRequests = true)

  /** Copies releases. */
  def withReleases: MigrateRepository = copy(includesReleases = true)

  /** Copies the wiki. */
  def withWiki: MigrateRepository = copy(includesWiki = true)

  /** Asks for every content flag at once, leaving the credentials, the mirror settings and LFS alone. */
  def withEverything: MigrateRepository =
    copy(
      includesIssues       = true,
      includesLabels       = true,
      includesMilestones   = true,
      includesPullRequests = true,
      includesReleases     = true,
      includesWiki         = true,
    )

object MigrateRepository:

  /** Starts a command from the two things Forgejo insists on.
    *
    * Cannot fail. `cloneAddress` is not validated: Forgejo accepts `https://`, `git://`, `ssh://` and local-path forms
    * and decides for itself which of them this instance permits, so a smart constructor here could only reject
    * something that would have worked.
    *
    * @param cloneAddress
    *   the URL to fetch from
    * @param repoName
    *   what the new repository will be called
    */
  def from(cloneAddress: String, repoName: RepoName): MigrateRepository =
    MigrateRepository(
      cloneAddress         = cloneAddress,
      repoName             = repoName,
      repoOwner            = None,
      description          = None,
      service              = None,
      username             = None,
      credential           = None,
      token                = None,
      isPrivate            = false,
      isMirror             = false,
      mirrorInterval       = None,
      lfs                  = false,
      lfsEndpoint          = None,
      includesIssues       = false,
      includesLabels       = false,
      includesMilestones   = false,
      includesPullRequests = false,
      includesReleases     = false,
      includesWiki         = false,
    )

/** Everything `POST /repos/{owner}/{repo}/transfer` may be told, as one value.
  *
  * Derived from `TransferRepoOption` in `spec/swagger.v1.json`, which declares `new_owner` required and `team_ids`
  * optional. No golden capture of this request exists.
  *
  * ==A transfer is a proposal, not a move==
  *
  * Forgejo answers `202`, not `200`: the repository stays where it is, marked as pending transfer, until the receiving
  * side calls `RepositoryAdminApi.acceptTransfer` or `RepositoryAdminApi.rejectTransfer`. A caller who transfers to an
  * organisation they own may find the transfer completes immediately instead, because Forgejo skips the confirmation
  * when the same account is on both sides. Neither outcome is distinguishable from the `202` alone; read the repository
  * back to find out which happened.
  *
  * @param newOwner
  *   the user or organisation that will receive the repository
  * @param teamIds
  *   the teams to grant access to afterwards. Only meaningful when [[newOwner]] is an organisation; Forgejo rejects
  *   them otherwise
  */
final case class TransferRepository(newOwner: Owner, teamIds: Vector[TeamId]):

  /** Grants `team` access to the repository once the transfer completes. */
  def grantedTo(team: TeamId): TransferRepository = copy(teamIds = teamIds.appended(team))

object TransferRepository:

  /** Starts a transfer to `newOwner`, granting no team access.
    *
    * Cannot fail: the argument is an already-validated type.
    */
  def to(newOwner: Owner): TransferRepository =
    TransferRepository(newOwner = newOwner, teamIds = Vector.empty)
