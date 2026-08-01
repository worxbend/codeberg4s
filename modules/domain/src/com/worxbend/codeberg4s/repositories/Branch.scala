package com.worxbend.codeberg4s.repositories

/** A branch of a repository, together with the commit it points at and what the caller may do to it.
  *
  * The permission flags are answered from the perspective of the credentials that made the request, exactly as
  * [[RepositoryPermissions]] is: an anonymous call to a public repository reports `false` for both, which is what
  * `golden/repository/branches-list.json` shows.
  *
  * @param name
  *   the branch name; it may contain `/`, see [[BranchName]]
  * @param commit
  *   the commit at the branch's tip
  * @param isProtected
  *   whether a branch protection rule applies
  * @param requiredApprovals
  *   how many approving reviews a pull request into this branch needs; `0` when no rule applies
  * @param statusCheckEnabled
  *   whether merging requires status checks to pass
  * @param statusCheckContexts
  *   the check names that must report success; empty when [[statusCheckEnabled]] is `false`
  * @param userCanPush
  *   whether the credentials that made the request may push here
  * @param userCanMerge
  *   whether they may merge here
  * @param effectiveBranchProtectionName
  *   the name of the protection rule that matched, absent when none did. Forgejo sends `""` rather than `null` for
  *   "none", and that spelling is folded away before it reaches this model
  */
final case class Branch(
    name: BranchName,
    commit: CommitSummary,
    isProtected: Boolean,
    requiredApprovals: Long,
    statusCheckEnabled: Boolean,
    statusCheckContexts: Vector[String],
    userCanPush: Boolean,
    userCanMerge: Boolean,
    effectiveBranchProtectionName: Option[String],
)
