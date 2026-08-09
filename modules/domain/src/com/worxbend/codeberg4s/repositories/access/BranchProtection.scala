package com.worxbend.codeberg4s.repositories.access

import java.time.Instant

/** One branch protection rule — what Forgejo enforces on every branch whose name matches [[ruleName]].
  *
  * '''No golden fixture backs this model.''' `golden/MANIFEST.md` records that every capture was taken anonymously and
  * `GET /repos/{owner}/{repo}/branch_protections` requires a token, so the twenty-seven fields below are exactly the
  * properties of `definitions.BranchProtection` in `spec/swagger.v1.json`, read literally. Per `docs/HAZARDS.md` §1 the
  * spec asserts nothing about optionality, so the decoder treats every field as absent-able and the conversion supplies
  * the reading below. Should a capture ever contradict this model, the capture wins.
  *
  * ==Every flag defaults to the permissive reading, and that is deliberate==
  *
  * A `Boolean` here that the payload did not carry becomes `false`. For most fields — [[requireSignedCommits]],
  * [[blockOnOutdatedBranch]], [[applyToAdmins]] — `false` means "this restriction is not in force", which is the
  * '''weaker''' of the two readings and therefore the one that will not tell a caller a branch is protected when the
  * instance never said so. An auditor reading this model can only ever under-state what is enforced, never over-state
  * it. `enable_push` is the one field where `false` reads as "nobody may push", which is the stricter direction; it is
  * also a field Forgejo always sends, so the case is theoretical.
  *
  * ==One field name in this model is not the plural it looks like==
  *
  * `push_whitelist_usernames` and `merge_whitelist_usernames` are plural. The approvals list is
  * `approvals_whitelist_username` — '''singular''' — in the spec, in the read model and in both request models. That is
  * Forgejo's spelling, not a transcription error, and it is written down once, in
  * `com.worxbend.codeberg4s.repositories.access.wire.BranchProtectionWire`. A client that "corrects" it sends a field
  * the instance ignores, and the resulting rule has an empty approvals whitelist while the caller believes otherwise.
  *
  * @param ruleName
  *   the glob the rule matches branches with. A plain `String`, not a [[BranchRuleName]]: a rule name containing `/`
  *   cannot be addressed by the by-name endpoints, and dropping such a rule from a listing would cost the caller the
  *   information they asked for. [[BranchRuleName.from]] is how a caller asks whether a given rule is addressable
  * @param legacyBranchName
  *   the `branch_name` property, which the spec marks deprecated in favour of `rule_name`. Present on older instances
  *   and carried so nothing is lost; when a payload carries only this one, [[ruleName]] is taken from it
  * @param requiredApprovals
  *   how many approving reviews a pull request needs before it may be merged; `0` when the payload did not say, which
  *   reads as "no approvals required"
  * @param protectedFilePatterns
  *   a glob list, semicolon-separated by Forgejo's own convention, of files nobody may change on a matching branch.
  *   Kept as the instance's own string rather than split, because the separator is Forgejo's and undocumented in the
  *   spec
  * @param unprotectedFilePatterns
  *   the counterpart carve-out, in the same spelling
  * @param applyToAdmins
  *   whether repository administrators are bound by this rule too. `false` — the default reading — means they are not
  */
final case class BranchProtection private[codeberg4s] (
    ruleName: String,
    legacyBranchName: Option[String],
    enablePush: Boolean,
    enablePushWhitelist: Boolean,
    pushWhitelistUsernames: Vector[String],
    pushWhitelistTeams: Vector[String],
    pushWhitelistDeployKeys: Boolean,
    enableMergeWhitelist: Boolean,
    mergeWhitelistUsernames: Vector[String],
    mergeWhitelistTeams: Vector[String],
    enableStatusCheck: Boolean,
    statusCheckContexts: Vector[String],
    requiredApprovals: Long,
    enableApprovalsWhitelist: Boolean,
    approvalsWhitelistUsernames: Vector[String],
    approvalsWhitelistTeams: Vector[String],
    blockOnRejectedReviews: Boolean,
    blockOnOfficialReviewRequests: Boolean,
    blockOnOutdatedBranch: Boolean,
    dismissStaleApprovals: Boolean,
    ignoreStaleApprovals: Boolean,
    requireSignedCommits: Boolean,
    protectedFilePatterns: Option[String],
    unprotectedFilePatterns: Option[String],
    applyToAdmins: Boolean,
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
