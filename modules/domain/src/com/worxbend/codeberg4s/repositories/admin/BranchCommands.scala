package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.repositories.BranchName

/** Everything `POST /repos/{owner}/{repo}/branches` may be told, as one value.
  *
  * Derived from `CreateBranchRepoOption` in `spec/swagger.v1.json`, which declares `new_branch_name` required. No
  * golden capture of this request exists.
  *
  * ==Only one of the two "from" fields is modelled==
  *
  * The wire model has both `old_branch_name` and `old_ref_name`, and the spec marks the first `Deprecated: true`. Only
  * the second is offered here, as [[startingAt]]: it accepts a branch, a tag or a commit id, so it is strictly more
  * capable, and carrying a deprecated alias would mean this library had to decide what happens when a caller sets both.
  *
  * Setting neither is valid and asks Forgejo to branch from the repository's default branch.
  *
  * @param newBranchName
  *   the branch to create
  * @param fromRef
  *   the branch, tag or commit id to create it at. Absent means the repository's default branch
  */
final case class CreateBranch(newBranchName: BranchName, fromRef: Option[String]):

  /** Creates the branch at `ref`, which may name a branch, a tag or a commit. */
  def startingAt(ref: String): CreateBranch = copy(fromRef = Some(ref))

object CreateBranch:

  /** Starts a command that branches from the repository's default branch.
    *
    * Cannot fail: the argument is an already-validated type.
    */
  def named(newBranchName: BranchName): CreateBranch =
    CreateBranch(newBranchName = newBranchName, fromRef = None)

/** Everything `PATCH /repos/{owner}/{repo}/branches/{branch}` may be told, as one value.
  *
  * Derived from `UpdateBranchRepoOption` in `spec/swagger.v1.json`, which declares one required property, `name`. No
  * golden capture of this request exists.
  *
  * '''This endpoint only renames.''' Despite the "update a branch" summary, `name` is the sole property the wire model
  * has: there is nothing else about a branch this call can change. Moving a branch to a different commit is a Git push
  * or `RepositoryGitApi`'s reference endpoints, not this.
  *
  * Renaming the default branch is the one case that has consequences elsewhere — Forgejo moves the default with it, and
  * every open pull request targeting the old name is retargeted.
  *
  * @param newName
  *   what the branch will be called
  */
final case class RenameBranch(newName: BranchName)
