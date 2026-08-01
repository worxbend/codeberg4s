package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Repository` model, field for field.
  *
  * Every key observed across the thirteen repository objects in the golden fixtures —
  * `golden/repository/repo-single.json`, `repo-single-community.json`, `search.json`, `forks-list.json`,
  * `golden/user/user-repos-list.json` and `golden/organization/org-repos-list.json` — is represented here. Two keys are
  * deliberately not:
  *
  *   - `repo_transfer` is `null` on all thirteen. It carries a pending-transfer record whose payload includes a `Team`
  *     model no wave owns yet, and inventing a shape for a field nobody has ever seen populated would be a guess.
  *   - `external_tracker` and `external_wiki` never appear at all — `docs/HAZARDS.md` records that Forgejo omits them
  *     entirely when unconfigured — so there is no observed evidence to model them from. They belong to the wave that
  *     first meets a repository using an external tracker.
  *
  * Both omissions are safe: [[com.worxbend.codeberg4s.codec.JsonFields]] ignores keys the DTO does not name, so a
  * payload carrying them still decodes.
  *
  * `parent` recurses into this same DTO. Forgejo populates it for forks and sends `null` otherwise, so the recursion
  * terminates on the wire rather than by depth limit.
  *
  * Timestamps stay as raw strings; [[com.worxbend.codeberg4s.codec.Timestamps]] normalises them, sentinels included,
  * during conversion.
  */
final case class RepositoryDto(
    id: Option[Long],
    owner: Option[UserDto],
    name: Option[String],
    fullName: Option[String],
    description: Option[String],
    empty: Option[Boolean],
    isPrivate: Option[Boolean],
    fork: Option[Boolean],
    template: Option[Boolean],
    parent: Option[RepositoryDto],
    mirror: Option[Boolean],
    size: Option[Long],
    language: Option[String],
    languagesUrl: Option[String],
    htmlUrl: Option[String],
    url: Option[String],
    link: Option[String],
    sshUrl: Option[String],
    cloneUrl: Option[String],
    originalUrl: Option[String],
    website: Option[String],
    starsCount: Option[Long],
    forksCount: Option[Long],
    watchersCount: Option[Long],
    openIssuesCount: Option[Long],
    openPrCounter: Option[Long],
    releaseCounter: Option[Long],
    defaultBranch: Option[String],
    archived: Option[Boolean],
    createdAt: Option[String],
    updatedAt: Option[String],
    archivedAt: Option[String],
    permissions: Option[PermissionDto],
    hasIssues: Option[Boolean],
    internalTracker: Option[InternalTrackerDto],
    hasWiki: Option[Boolean],
    hasWikiContents: Option[Boolean],
    wikiBranch: Option[String],
    wikiSshUrl: Option[String],
    wikiCloneUrl: Option[String],
    globallyEditableWiki: Option[Boolean],
    hasPullRequests: Option[Boolean],
    hasProjects: Option[Boolean],
    hasReleases: Option[Boolean],
    hasPackages: Option[Boolean],
    hasActions: Option[Boolean],
    ignoreWhitespaceConflicts: Option[Boolean],
    allowMergeCommits: Option[Boolean],
    allowRebase: Option[Boolean],
    allowRebaseExplicit: Option[Boolean],
    allowSquashMerge: Option[Boolean],
    allowFastForwardOnlyMerge: Option[Boolean],
    allowRebaseUpdate: Option[Boolean],
    defaultDeleteBranchAfterMerge: Option[Boolean],
    defaultMergeStyle: Option[String],
    defaultAllowMaintainerEdit: Option[Boolean],
    defaultUpdateStyle: Option[String],
    avatarUrl: Option[String],
    internal: Option[Boolean],
    mirrorInterval: Option[String],
    objectFormatName: Option[String],
    mirrorUpdated: Option[String],
    topics: Vector[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Four things are required, because without them there is no repository to speak of: `id`, `name`, `owner`, and the
    * owner's `login`. The last two are what build the [[com.worxbend.codeberg4s.repositories.RepoSlug]], and the slug
    * is what every other repository endpoint takes as its argument — a `Repository` that cannot address itself would be
    * useless. `name` and the owner's `login` additionally go through their smart constructors, so a value containing a
    * slash is rejected here rather than forging a path later.
    *
    * Everything else is optional or defaulted. Absent counts become `0`, absent flags become `false`, and an absent
    * `topics` becomes an empty `Vector` — the reduced repository objects Forgejo embeds in pull requests and
    * notifications carry none of them.
    *
    * A failure inside `owner` or `parent` is reported at that nested path, not at the repository's.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Repository] =
    for
      identifier  <- Wire.required(at, "id", id)
      repoName    <- Wire.validated(at, "name", name)(RepoName.from)
      ownerDto    <- Wire.required(at, "owner", owner)
      ownerModel  <- ownerDto.toDomainAt(at.field("owner"))
      ownerHandle <- Wire.validated(at.field("owner"), "login", ownerDto.login)(Owner.from)
      parentModel <- parentAt(at)
    yield Repository(
      id                   = identifier,
      slug                 = RepoSlug(ownerHandle, repoName),
      fullName             = fullName.getOrElse(s"${ownerHandle.value}/${repoName.value}"),
      owner                = ownerModel,
      description          = description,
      htmlUrl              = htmlUrl,
      cloneUrl             = cloneUrl,
      sshUrl               = sshUrl,
      originalUrl          = originalUrl,
      website              = website,
      defaultBranch        = defaultBranch,
      language             = language,
      avatarUrl            = avatarUrl,
      topics               = topics,
      sizeKb               = size.getOrElse(0L),
      starsCount           = starsCount.getOrElse(0L),
      forksCount           = forksCount.getOrElse(0L),
      watchersCount        = watchersCount.getOrElse(0L),
      openIssuesCount      = openIssuesCount.getOrElse(0L),
      openPullRequestCount = openPrCounter.getOrElse(0L),
      releaseCount         = releaseCounter.getOrElse(0L),
      isPrivate            = isPrivate.getOrElse(false),
      isInternal           = internal.getOrElse(false),
      isFork               = fork.getOrElse(false),
      isTemplate           = template.getOrElse(false),
      isMirror             = mirror.getOrElse(false),
      isArchived           = archived.getOrElse(false),
      isEmpty              = empty.getOrElse(false),
      hasIssues            = hasIssues.getOrElse(false),
      hasWiki              = hasWiki.getOrElse(false),
      hasPullRequests      = hasPullRequests.getOrElse(false),
      hasProjects          = hasProjects.getOrElse(false),
      hasReleases          = hasReleases.getOrElse(false),
      hasPackages          = hasPackages.getOrElse(false),
      hasActions           = hasActions.getOrElse(false),
      permissions          = permissions.map(_.toDomain),
      parent               = parentModel,
      createdAt            = Timestamps.parseOptional(createdAt),
      updatedAt            = Timestamps.parseOptional(updatedAt),
      archivedAt           = Timestamps.parseOptional(archivedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Repository] =
    toDomainAt(JsonPath.Root)

  private def parentAt(at: JsonPath): Either[DecodeFailure, Option[Repository]] =
    parent.fold(Right(None))(dto => dto.toDomainAt(at.field("parent")).map(Some.apply))

object RepositoryDto:

  /** Reads a `Repository` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[RepositoryDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object.
    *
    * Recurses through `parent`, and reuses [[com.worxbend.codeberg4s.users.wire.UserDto.fromFields]] for `owner`, so
    * the user field spellings are not repeated here.
    */
  def fromFields(fields: JsonFields): RepositoryDto =
    RepositoryDto(
      id                            = fields.number("id"),
      owner                         = fields.nested("owner").map(UserDto.fromFields),
      name                          = fields.text("name"),
      fullName                      = fields.text("full_name"),
      description                   = fields.text("description"),
      empty                         = fields.boolean("empty"),
      isPrivate                     = fields.boolean("private"),
      fork                          = fields.boolean("fork"),
      template                      = fields.boolean("template"),
      parent                        = fields.nested("parent").map(fromFields),
      mirror                        = fields.boolean("mirror"),
      size                          = fields.number("size"),
      language                      = fields.text("language"),
      languagesUrl                  = fields.text("languages_url"),
      htmlUrl                       = fields.text("html_url"),
      url                           = fields.text("url"),
      link                          = fields.text("link"),
      sshUrl                        = fields.text("ssh_url"),
      cloneUrl                      = fields.text("clone_url"),
      originalUrl                   = fields.text("original_url"),
      website                       = fields.text("website"),
      starsCount                    = fields.number("stars_count"),
      forksCount                    = fields.number("forks_count"),
      watchersCount                 = fields.number("watchers_count"),
      openIssuesCount               = fields.number("open_issues_count"),
      openPrCounter                 = fields.number("open_pr_counter"),
      releaseCounter                = fields.number("release_counter"),
      defaultBranch                 = fields.text("default_branch"),
      archived                      = fields.boolean("archived"),
      createdAt                     = fields.text("created_at"),
      updatedAt                     = fields.text("updated_at"),
      archivedAt                    = fields.text("archived_at"),
      permissions                   = fields.nested("permissions").map(PermissionDto.fromFields),
      hasIssues                     = fields.boolean("has_issues"),
      internalTracker               = fields.nested("internal_tracker").map(InternalTrackerDto.fromFields),
      hasWiki                       = fields.boolean("has_wiki"),
      hasWikiContents               = fields.boolean("has_wiki_contents"),
      wikiBranch                    = fields.text("wiki_branch"),
      wikiSshUrl                    = fields.text("wiki_ssh_url"),
      wikiCloneUrl                  = fields.text("wiki_clone_url"),
      globallyEditableWiki          = fields.boolean("globally_editable_wiki"),
      hasPullRequests               = fields.boolean("has_pull_requests"),
      hasProjects                   = fields.boolean("has_projects"),
      hasReleases                   = fields.boolean("has_releases"),
      hasPackages                   = fields.boolean("has_packages"),
      hasActions                    = fields.boolean("has_actions"),
      ignoreWhitespaceConflicts     = fields.boolean("ignore_whitespace_conflicts"),
      allowMergeCommits             = fields.boolean("allow_merge_commits"),
      allowRebase                   = fields.boolean("allow_rebase"),
      allowRebaseExplicit           = fields.boolean("allow_rebase_explicit"),
      allowSquashMerge              = fields.boolean("allow_squash_merge"),
      allowFastForwardOnlyMerge     = fields.boolean("allow_fast_forward_only_merge"),
      allowRebaseUpdate             = fields.boolean("allow_rebase_update"),
      defaultDeleteBranchAfterMerge = fields.boolean("default_delete_branch_after_merge"),
      defaultMergeStyle             = fields.text("default_merge_style"),
      defaultAllowMaintainerEdit    = fields.boolean("default_allow_maintainer_edit"),
      defaultUpdateStyle            = fields.text("default_update_style"),
      avatarUrl                     = fields.text("avatar_url"),
      internal                      = fields.boolean("internal"),
      mirrorInterval                = fields.text("mirror_interval"),
      objectFormatName              = fields.text("object_format_name"),
      mirrorUpdated                 = fields.text("mirror_updated"),
      topics                        = fields.texts("topics"),
    )
