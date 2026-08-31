package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.BranchName

/** Where a repository's issues live when they are not in Forgejo — the `external_tracker` object of `EditRepoOption`.
  *
  * Setting this switches the repository's issue tab to a link. Forgejo stops storing issues for it; the ones already
  * stored are not deleted, they simply become unreachable through the UI until the setting is removed.
  *
  * @param url
  *   the tracker's base URL
  * @param format
  *   the URL pattern one issue is reached by, with the `{user}`, `{repo}` and `{index}` placeholders Forgejo
  *   substitutes
  * @param style
  *   how issue references in commit messages are recognised: `numeric`, `alphanumeric` or `regexp`. Left as text
  *   because the spec types it as a bare string and names the three values only in prose
  * @param regexpPattern
  *   the pattern used when [[style]] is `regexp`
  */
final case class ExternalTrackerSettings(
    url: Option[String],
    format: Option[String],
    style: Option[String],
    regexpPattern: Option[String],
)

/** Where a repository's wiki lives when it is not in Forgejo — the `external_wiki` object of `EditRepoOption`.
  *
  * @param url
  *   the wiki's URL, which the repository's wiki tab becomes a link to
  */
final case class ExternalWikiSettings(url: Option[String])

/** How Forgejo's own issue tracker behaves for a repository — the `internal_tracker` object of `EditRepoOption`.
  *
  * @param enableTimeTracker
  *   whether time may be recorded against issues at all. The tracked-time endpoints answer `404` when this is off
  * @param allowOnlyContributorsToTrackTime
  *   whether recording time is limited to contributors
  * @param enableIssueDependencies
  *   whether issues may block one another
  */
final case class InternalTrackerSettings(
    enableTimeTracker: Option[Boolean],
    allowOnlyContributorsToTrackTime: Option[Boolean],
    enableIssueDependencies: Option[Boolean],
)

/** Everything `PATCH /repos/{owner}/{repo}` may be told, as one value.
  *
  * Derived from `EditRepoOption` in `spec/swagger.v1.json`, which declares nothing required and thirty-five optional
  * properties. No golden capture of this request exists.
  *
  * ==Only what is set is sent, and that is the whole contract==
  *
  * The endpoint's own summary is "Only fields that are set will be changed". Every property here is therefore an
  * `Option` and the renderer omits every `None`, so an [[EditRepository.Empty]] sent as-is changes nothing. This is why
  * the Boolean settings are `Option[Boolean]` rather than `Boolean`: `false` and "leave it alone" are different
  * requests, and a plain `Boolean` would silently turn every unmentioned feature off.
  *
  * ==Two of these have consequences past this call==
  *
  *   - [[name]] renames the repository, which changes every URL it is reachable at. Old URLs `404`; Forgejo installs no
  *     redirect for API paths.
  *   - [[archived]] freezes the repository. Every write endpoint in this library then answers `423`, including the
  *     contents writes and the branch writes in this very group. Un-archiving is the same field set back to `false`.
  *
  * @param name
  *   a new name for the repository. See the note above
  * @param description
  *   a new description
  * @param website
  *   a new website URL
  * @param isPrivate
  *   whether the repository is private. Forgejo answers `422` when an organisation restricts visibility changes to
  *   owners and the caller is not one
  * @param isTemplate
  *   whether the repository may be generated from
  * @param defaultBranch
  *   the branch clones check out and pull requests target by default
  * @param archived
  *   whether the repository is frozen. See the note above
  * @param hasIssues
  *   whether the issue tracker is enabled
  * @param hasWiki
  *   whether the wiki is enabled
  * @param hasPullRequests
  *   whether pull requests are accepted
  * @param hasProjects
  *   whether the project boards are enabled
  * @param hasReleases
  *   whether releases are enabled
  * @param hasPackages
  *   whether the package registry is enabled
  * @param hasActions
  *   whether Actions are enabled. Turning this off makes every endpoint on `RepositoryActionApi` answer `403`
  * @param allowMergeCommits
  *   whether a pull request may be merged with a merge commit
  * @param allowRebase
  *   whether a pull request may be rebase-merged
  * @param allowRebaseExplicit
  *   whether a pull request may be rebased with an explicit merge commit
  * @param allowSquashMerge
  *   whether a pull request may be squash-merged
  * @param allowFastForwardOnlyMerge
  *   whether a pull request may be merged only when it is already a fast-forward
  * @param allowManualMerge
  *   whether a pull request may be marked merged by hand
  * @param allowRebaseUpdate
  *   whether a pull request branch may be updated by rebasing
  * @param autodetectManualMerge
  *   whether Forgejo tries to notice a merge that happened outside it. The spec warns this misjudges in some cases
  * @param defaultDeleteBranchAfterMerge
  *   whether the head branch is deleted once a pull request merges
  * @param defaultAllowMaintainerEdit
  *   whether maintainers may push to a pull request's head branch by default
  * @param defaultMergeStyle
  *   the merge style used when a caller names none — see [[MergeStyle]]
  * @param defaultUpdateStyle
  *   the update style used when a caller names none — see [[UpdateStyle]]
  * @param ignoreWhitespaceConflicts
  *   whether whitespace-only differences count as conflicts
  * @param enablePrune
  *   whether mirroring removes remote-tracking references that vanished upstream
  * @param mirrorInterval
  *   how often a pull mirror runs, in Go's duration spelling such as `8h0m0s`
  * @param wikiBranch
  *   the branch the wiki's content lives on
  * @param globallyEditableWiki
  *   whether anyone signed in may edit the wiki
  * @param externalTracker
  *   moves the issue tracker off the instance — see [[ExternalTrackerSettings]]
  * @param externalWiki
  *   moves the wiki off the instance — see [[ExternalWikiSettings]]
  * @param internalTracker
  *   configures Forgejo's own tracker — see [[InternalTrackerSettings]]
  */
final case class EditRepository(
    name: Option[RepoName],
    description: Option[String],
    website: Option[String],
    isPrivate: Option[Boolean],
    isTemplate: Option[Boolean],
    defaultBranch: Option[BranchName],
    archived: Option[Boolean],
    hasIssues: Option[Boolean],
    hasWiki: Option[Boolean],
    hasPullRequests: Option[Boolean],
    hasProjects: Option[Boolean],
    hasReleases: Option[Boolean],
    hasPackages: Option[Boolean],
    hasActions: Option[Boolean],
    allowMergeCommits: Option[Boolean],
    allowRebase: Option[Boolean],
    allowRebaseExplicit: Option[Boolean],
    allowSquashMerge: Option[Boolean],
    allowFastForwardOnlyMerge: Option[Boolean],
    allowManualMerge: Option[Boolean],
    allowRebaseUpdate: Option[Boolean],
    autodetectManualMerge: Option[Boolean],
    defaultDeleteBranchAfterMerge: Option[Boolean],
    defaultAllowMaintainerEdit: Option[Boolean],
    defaultMergeStyle: Option[MergeStyle],
    defaultUpdateStyle: Option[UpdateStyle],
    ignoreWhitespaceConflicts: Option[Boolean],
    enablePrune: Option[Boolean],
    mirrorInterval: Option[String],
    wikiBranch: Option[BranchName],
    globallyEditableWiki: Option[Boolean],
    externalTracker: Option[ExternalTrackerSettings],
    externalWiki: Option[ExternalWikiSettings],
    internalTracker: Option[InternalTrackerSettings],
):

  /** Renames the repository. Every URL it is reachable at changes — see the type note. */
  def renamedTo(newName: RepoName): EditRepository = copy(name = Some(newName))

  /** Replaces the description. */
  def describedAs(text: String): EditRepository = copy(description = Some(text))

  /** Replaces the website URL. */
  def linkingTo(url: String): EditRepository = copy(website = Some(url))

  /** Makes the repository private. */
  def madePrivate: EditRepository = copy(isPrivate = Some(true))

  /** Makes the repository public. */
  def madePublic: EditRepository = copy(isPrivate = Some(false))

  /** Moves the default branch. */
  def defaultingTo(branch: BranchName): EditRepository = copy(defaultBranch = Some(branch))

  /** Archives the repository, making it read-only. See the type note for what that does to every other write. */
  def archivedRepository: EditRepository = copy(archived = Some(true))

  /** Un-archives the repository. */
  def unarchivedRepository: EditRepository = copy(archived = Some(false))

  /** Turns the issue tracker on or off. */
  def withIssues(enabled: Boolean): EditRepository = copy(hasIssues = Some(enabled))

  /** Turns the wiki on or off. */
  def withWiki(enabled: Boolean): EditRepository = copy(hasWiki = Some(enabled))

  /** Turns pull requests on or off. */
  def withPullRequests(enabled: Boolean): EditRepository = copy(hasPullRequests = Some(enabled))

  /** Turns Actions on or off. See the field note for what turning it off does to `RepositoryActionApi`. */
  def withActions(enabled: Boolean): EditRepository = copy(hasActions = Some(enabled))

  /** Sets the merge style used when a caller names none. */
  def mergingBy(style: MergeStyle): EditRepository = copy(defaultMergeStyle = Some(style))

  /** Sets the update style used when a caller names none. */
  def updatingBy(style: UpdateStyle): EditRepository = copy(defaultUpdateStyle = Some(style))

  /** Sets how often a pull mirror runs, in Go's duration spelling such as `8h0m0s`. */
  def mirroringEvery(duration: String): EditRepository = copy(mirrorInterval = Some(duration))

  /** Moves the issue tracker off the instance. */
  def trackingIssuesAt(settings: ExternalTrackerSettings): EditRepository = copy(externalTracker = Some(settings))

  /** Moves the wiki off the instance. */
  def hostingWikiAt(settings: ExternalWikiSettings): EditRepository = copy(externalWiki = Some(settings))

  /** Configures Forgejo's own issue tracker. */
  def trackingIssuesWith(settings: InternalTrackerSettings): EditRepository = copy(internalTracker = Some(settings))

  /** Whether this command would change nothing, because no property was set.
    *
    * Worth checking before calling: an empty edit is a perfectly valid request that costs a round trip and returns the
    * repository unchanged, which is rarely what the code meant to do.
    */
  def isEmpty: Boolean = this.equals(EditRepository.Empty)

object EditRepository:

  /** An edit that changes nothing. Every command starts here and sets only what it means to change. */
  val Empty: EditRepository =
    EditRepository(
      name                          = None,
      description                   = None,
      website                       = None,
      isPrivate                     = None,
      isTemplate                    = None,
      defaultBranch                 = None,
      archived                      = None,
      hasIssues                     = None,
      hasWiki                       = None,
      hasPullRequests               = None,
      hasProjects                   = None,
      hasReleases                   = None,
      hasPackages                   = None,
      hasActions                    = None,
      allowMergeCommits             = None,
      allowRebase                   = None,
      allowRebaseExplicit           = None,
      allowSquashMerge              = None,
      allowFastForwardOnlyMerge     = None,
      allowManualMerge              = None,
      allowRebaseUpdate             = None,
      autodetectManualMerge         = None,
      defaultDeleteBranchAfterMerge = None,
      defaultAllowMaintainerEdit    = None,
      defaultMergeStyle             = None,
      defaultUpdateStyle            = None,
      ignoreWhitespaceConflicts     = None,
      enablePrune                   = None,
      mirrorInterval                = None,
      wikiBranch                    = None,
      globallyEditableWiki          = None,
      externalTracker               = None,
      externalWiki                  = None,
      internalTracker               = None,
    )
