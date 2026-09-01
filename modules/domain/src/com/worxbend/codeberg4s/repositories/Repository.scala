package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.repositories.publishing.Topic
import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** A repository on a Codeberg or Forgejo instance.
  *
  * Curated, not generated (ADR-0001). Forgejo's `Repository` carries 66 keys; roughly half of them configure the merge
  * button, the wiki and the mirror scheduler, and belong to the settings API rather than to a read model. What survives
  * here is identity, the counts a caller displays, the flags a caller branches on, and the timestamps a caller sorts
  * by. The full payload is preserved in [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] for anyone who
  * needs the rest.
  *
  * `parent` is populated for forks and recurses into the same model — `golden/repository/forks-list.json` shows a fork
  * whose parent is a complete repository object, while `golden/repository/repo-single.json` sends `parent: null`.
  *
  * @param id
  *   the instance-wide identifier, stable across renames and transfers, and what `repos.admin.byId` takes
  * @param slug
  *   the validated `owner/name` pair, safe to interpolate into a request path
  * @param fullName
  *   Forgejo's own `owner/name` rendering, which preserves the display casing the slug normalises
  * @param owner
  *   the account the repository belongs to; an organisation is a user here, see [[com.worxbend.codeberg4s.users.User]]
  * @param sizeKb
  *   the on-disk size in kibibytes, as Forgejo reports it
  * @param permissions
  *   what the caller may do, from the perspective of the credentials that made the request
  * @param defaultBranch
  *   the branch a clone checks out, already validated so it can be handed to any branch endpoint; absent on an empty
  *   repository
  * @param topics
  *   the repository's topics, already validated so they can be handed back to the topic endpoints
  * @param archivedAt
  *   absent unless the repository is archived; Forgejo sends the Unix epoch as its "never" sentinel
  */
final case class Repository private[codeberg4s] (
    id: RepositoryId,
    slug: RepoSlug,
    fullName: String,
    owner: User,
    description: Option[String],
    htmlUrl: Option[String],
    cloneUrl: Option[String],
    sshUrl: Option[String],
    originalUrl: Option[String],
    website: Option[String],
    defaultBranch: Option[BranchName],
    language: Option[String],
    avatarUrl: Option[String],
    topics: Vector[Topic],
    sizeKb: Long,
    starsCount: Long,
    forksCount: Long,
    watchersCount: Long,
    openIssuesCount: Long,
    openPullRequestCount: Long,
    releaseCount: Long,
    isPrivate: Boolean,
    isInternal: Boolean,
    isFork: Boolean,
    isTemplate: Boolean,
    isMirror: Boolean,
    isArchived: Boolean,
    isEmpty: Boolean,
    hasIssues: Boolean,
    hasWiki: Boolean,
    hasPullRequests: Boolean,
    hasProjects: Boolean,
    hasReleases: Boolean,
    hasPackages: Boolean,
    hasActions: Boolean,
    permissions: Option[RepositoryPermissions],
    parent: Option[Repository],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
    archivedAt: Option[Instant],
)
