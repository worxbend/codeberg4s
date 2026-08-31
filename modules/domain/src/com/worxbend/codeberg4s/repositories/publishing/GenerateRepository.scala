package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.BranchName

/** Everything `POST /repos/{template_owner}/{template_repo}/generate` may be told, as one value.
  *
  * Derived from `GenerateRepoOption` in `spec/swagger.v1.json`, which declares `owner` and `name` required and ten
  * optional properties, seven of them "include this part of the template" flags. No golden capture of this request
  * exists.
  *
  * '''The seven include-flags all default to `false`, and the one that surprises people is `git_content`.''' A generate
  * call that does not ask for it produces a repository with the template's settings and no commits at all.
  * [[withGitContent]] is how a caller asks for the thing they almost certainly meant; [[everything]] asks for all seven
  * at once.
  *
  * ==Which repository is which==
  *
  * The '''template''' is named by the request path, not by this command — it is the pair of arguments to
  * `RepositoryPublishingApi.generate`. Everything here describes the repository being '''created'''. Keeping the two
  * apart is why the type is called `GenerateRepository` and not `GenerateRepoOption`.
  *
  * @param owner
  *   the user or organisation that will own the new repository. Required by Forgejo
  * @param name
  *   the name of the new repository. Required by Forgejo
  * @param description
  *   the new repository's description
  * @param defaultBranch
  *   the new repository's default branch. Absent leaves Forgejo's own default
  * @param isPrivate
  *   whether the new repository is private
  * @param includesAvatar
  *   copy the template's avatar
  * @param includesGitContent
  *   copy the template's default branch — its commits. See the note above
  * @param includesGitHooks
  *   copy the template's Git hooks; needs the privilege to set hooks on the instance
  * @param includesLabels
  *   copy the template's issue labels
  * @param includesProtectedBranches
  *   copy the template's branch protection rules
  * @param includesTopics
  *   copy the template's topics
  * @param includesWebhooks
  *   copy the template's webhooks
  */
final case class GenerateRepository(
    owner: Owner,
    name: RepoName,
    description: Option[String],
    defaultBranch: Option[BranchName],
    isPrivate: Boolean,
    includesAvatar: Boolean,
    includesGitContent: Boolean,
    includesGitHooks: Boolean,
    includesLabels: Boolean,
    includesProtectedBranches: Boolean,
    includesTopics: Boolean,
    includesWebhooks: Boolean,
):

  /** Describes the new repository. */
  def describedAs(text: String): GenerateRepository = copy(description = Some(text))

  /** Names the new repository's default branch. */
  def defaultingTo(branch: BranchName): GenerateRepository = copy(defaultBranch = Some(branch))

  /** Creates the repository private. */
  def asPrivate: GenerateRepository = copy(isPrivate = true)

  /** Copies the template's avatar. */
  def withAvatar: GenerateRepository = copy(includesAvatar = true)

  /** Copies the template's commits. Almost always wanted — see the type's own note. */
  def withGitContent: GenerateRepository = copy(includesGitContent = true)

  /** Copies the template's Git hooks. */
  def withGitHooks: GenerateRepository = copy(includesGitHooks = true)

  /** Copies the template's issue labels. */
  def withLabels: GenerateRepository = copy(includesLabels = true)

  /** Copies the template's branch protection rules. */
  def withProtectedBranches: GenerateRepository = copy(includesProtectedBranches = true)

  /** Copies the template's topics. */
  def withTopics: GenerateRepository = copy(includesTopics = true)

  /** Copies the template's webhooks. */
  def withWebhooks: GenerateRepository = copy(includesWebhooks = true)

  /** Asks for all seven include-flags at once, leaving [[isPrivate]], [[description]] and [[defaultBranch]] alone. */
  def everything: GenerateRepository =
    copy(
      includesAvatar            = true,
      includesGitContent        = true,
      includesGitHooks          = true,
      includesLabels            = true,
      includesProtectedBranches = true,
      includesTopics            = true,
      includesWebhooks          = true,
    )

object GenerateRepository:

  /** Starts a command from the two things Forgejo insists on.
    *
    * Cannot fail: both arguments are already-validated types, so there is nothing left for this constructor to check.
    *
    * @param owner
    *   who will own the new repository
    * @param name
    *   what the new repository will be called
    */
  def of(owner: Owner, name: RepoName): GenerateRepository =
    GenerateRepository(
      owner                     = owner,
      name                      = name,
      description               = None,
      defaultBranch             = None,
      isPrivate                 = false,
      includesAvatar            = false,
      includesGitContent        = false,
      includesGitHooks          = false,
      includesLabels            = false,
      includesProtectedBranches = false,
      includesTopics            = false,
      includesWebhooks          = false,
    )
