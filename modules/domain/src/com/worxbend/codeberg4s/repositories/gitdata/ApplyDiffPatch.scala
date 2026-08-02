package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.BranchName

import java.time.Instant

/** The command `POST /repos/{owner}/{repo}/diffpatch` takes: a patch, and how to commit it.
  *
  * ==This writes to the repository==
  *
  * Applying a patch creates a commit on a branch. There is no dry-run mode and no idempotency key, which is why the
  * client's `applyDiffPatch` is never retried: a repeated `POST` that succeeded the first time leaves a second commit.
  *
  * ==What goes in [[content]]==
  *
  * The wire model is Forgejo's `UpdateFileOptions`, shared with the file-editing endpoints, and its `content` property
  * is documented there as base64-encoded. '''This library sends [[content]] verbatim and encodes nothing''': what the
  * diffpatch handler does with the value is the instance's business, the two endpoints that share the model do not
  * obviously agree about it, and guessing wrong in either direction would corrupt a patch silently. Encode before
  * calling if your instance wants base64.
  *
  * `UpdateFileOptions` also marks `sha` as required. It identifies the file being replaced, which a patch spanning
  * several files has no single answer for, so it is optional here and omitted unless a caller sets it.
  *
  * Built from [[ApplyDiffPatch.of]] and narrowed with the `with…` methods, so nothing is set by accident and no default
  * argument is needed.
  *
  * @param content
  *   the patch, sent as the `content` key
  * @param sha
  *   the `sha` key, when the caller has one to give
  * @param branch
  *   the branch to apply the patch to, absent for the repository's default branch
  * @param newBranch
  *   a branch to create from [[branch]] and commit to instead — how a patch becomes a pull request
  * @param message
  *   the commit message, absent for the instance's generated one
  * @param author
  *   who to attribute the commit to
  * @param committer
  *   who to record as having applied it
  * @param authorDate
  *   the value for `GIT_AUTHOR_DATE`
  * @param committerDate
  *   the value for `GIT_COMMITTER_DATE`
  * @param signoff
  *   whether to append a `Signed-off-by` trailer for the committer
  * @param forceOverwriteNewBranch
  *   whether to force-push [[newBranch]] if it already exists. Destructive, and `false` unless asked for
  */
final case class ApplyDiffPatch private (
    content: String,
    sha: Option[String],
    branch: Option[BranchName],
    newBranch: Option[BranchName],
    message: Option[String],
    author: Option[GitAuthor],
    committer: Option[GitAuthor],
    authorDate: Option[Instant],
    committerDate: Option[Instant],
    signoff: Boolean,
    forceOverwriteNewBranch: Boolean,
):

  /** This command, targeting `target` instead of the repository's default branch. */
  def onBranch(target: BranchName): ApplyDiffPatch = copy(branch = Some(target))

  /** This command, committing to a new branch cut from the target branch. */
  def onNewBranch(created: BranchName): ApplyDiffPatch = copy(newBranch = Some(created))

  /** This command, with an explicit commit message. */
  def withMessage(text: String): ApplyDiffPatch = copy(message = Some(text))

  /** This command, with the `sha` key set. See the type's own note on why it is optional. */
  def withSha(value: String): ApplyDiffPatch = copy(sha = Some(value))

  /** This command, attributed to `identity`. */
  def authoredBy(identity: GitAuthor): ApplyDiffPatch = copy(author = Some(identity))

  /** This command, committed by `identity`. */
  def committedBy(identity: GitAuthor): ApplyDiffPatch = copy(committer = Some(identity))

  /** This command, with explicit author and committer dates. */
  def dated(authored: Instant, committed: Instant): ApplyDiffPatch =
    copy(authorDate = Some(authored), committerDate = Some(committed))

  /** This command, with a `Signed-off-by` trailer. */
  def signedOff: ApplyDiffPatch = copy(signoff = true)

  /** This command, allowed to force-push [[newBranch]] over an existing branch of that name. */
  def forcingNewBranch: ApplyDiffPatch = copy(forceOverwriteNewBranch = true)

object ApplyDiffPatch:

  /** A patch to apply, with every optional decision left to the instance.
    *
    * Total: the patch is a request body and not a path segment, so there is nothing here a smart constructor could
    * reject that the instance is not better placed to reject itself with a `422`.
    */
  def of(patch: String): ApplyDiffPatch =
    ApplyDiffPatch(
      content                 = patch,
      sha                     = None,
      branch                  = None,
      newBranch               = None,
      message                 = None,
      author                  = None,
      committer               = None,
      authorDate              = None,
      committerDate           = None,
      signoff                 = false,
      forceOverwriteNewBranch = false,
    )
