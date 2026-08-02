package com.worxbend.codeberg4s.repositories.access.wire

/** The wire spelling of every property a branch protection rule has, written down exactly once.
  *
  * Rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a snake_case name appears once, as the literal
  * passed to a [[com.worxbend.codeberg4s.codec.JsonFields]] accessor. Branch protection is the one place in this
  * library where that is not enough on its own: the same twenty-five names appear in a '''reader''' — the rule Forgejo
  * sends back — and in '''two renderers''', the create and the edit bodies. Three copies of `push_whitelist_usernames`
  * is three chances for one of them to drift, and a request body that names a field the instance does not recognise is
  * silently dropped. The rule is then created, the caller sees `201`, and the branch is not protected the way they
  * asked.
  *
  * So the names live here, once, and [[BranchProtectionDto]], [[CreateBranchProtectionOptionDto]] and
  * [[EditBranchProtectionOptionDto]] all read them from this object rather than spelling them again.
  *
  * ==One of these is not the plural it looks like==
  *
  * [[ApprovalsWhitelistUsernames]] is `approvals_whitelist_username` — '''singular''' — while
  * [[PushWhitelistUsernames]] and [[MergeWhitelistUsernames]] are plural. That is verbatim from `spec/swagger.v1.json`,
  * on `BranchProtection`, on `CreateBranchProtectionOption` and on `EditBranchProtectionOption` alike; the Go field
  * behind it is `ApprovalsWhitelistUsernames`, so the JSON tag is the odd one out rather than the model. A client that
  * "corrects" the spelling sends a key Forgejo ignores and gets a rule with an empty approvals whitelist while its
  * caller believes otherwise. `BranchProtectionWireSuite` asserts this constant against the literal string for exactly
  * that reason.
  */
private[codeberg4s] object BranchProtectionWire:

  /** The glob the rule matches branches with — the property the by-name endpoints address it under. */
  val RuleName: String = "rule_name"

  /** The deprecated single-branch property `rule_name` replaced. */
  val BranchName: String = "branch_name"

  /** Whether pushing to a matching branch is permitted at all. */
  val EnablePush: String = "enable_push"

  /** Whether the push whitelist is consulted. */
  val EnablePushWhitelist: String = "enable_push_whitelist"

  /** The accounts on the push whitelist. Plural — unlike [[ApprovalsWhitelistUsernames]]. */
  val PushWhitelistUsernames: String = "push_whitelist_usernames"

  /** The teams on the push whitelist. */
  val PushWhitelistTeams: String = "push_whitelist_teams"

  /** Whether write-capable deploy keys bypass the push whitelist. */
  val PushWhitelistDeployKeys: String = "push_whitelist_deploy_keys"

  /** Whether the merge whitelist is consulted. */
  val EnableMergeWhitelist: String = "enable_merge_whitelist"

  /** The accounts on the merge whitelist. Plural — unlike [[ApprovalsWhitelistUsernames]]. */
  val MergeWhitelistUsernames: String = "merge_whitelist_usernames"

  /** The teams on the merge whitelist. */
  val MergeWhitelistTeams: String = "merge_whitelist_teams"

  /** Whether status checks gate a merge. */
  val EnableStatusCheck: String = "enable_status_check"

  /** The status check contexts that must report success. */
  val StatusCheckContexts: String = "status_check_contexts"

  /** How many approving reviews a pull request needs. */
  val RequiredApprovals: String = "required_approvals"

  /** Whether only whitelisted reviewers' approvals count. */
  val EnableApprovalsWhitelist: String = "enable_approvals_whitelist"

  /** The accounts whose approvals count.
    *
    * '''`approvals_whitelist_username`, singular.''' See the object note; this is not a typo to be fixed.
    */
  val ApprovalsWhitelistUsernames: String = "approvals_whitelist_username"

  /** The teams whose approvals count. */
  val ApprovalsWhitelistTeams: String = "approvals_whitelist_teams"

  /** Whether one rejecting review blocks the merge. */
  val BlockOnRejectedReviews: String = "block_on_rejected_reviews"

  /** Whether an outstanding official review request blocks the merge. */
  val BlockOnOfficialReviewRequests: String = "block_on_official_review_requests"

  /** Whether a branch behind its base must be updated before merging. */
  val BlockOnOutdatedBranch: String = "block_on_outdated_branch"

  /** Whether a new push discards existing approvals. */
  val DismissStaleApprovals: String = "dismiss_stale_approvals"

  /** Whether approvals of a superseded head stop counting. */
  val IgnoreStaleApprovals: String = "ignore_stale_approvals"

  /** Whether every commit must carry a trusted signature. */
  val RequireSignedCommits: String = "require_signed_commits"

  /** The protected-file glob list, in Forgejo's semicolon-separated spelling. */
  val ProtectedFilePatterns: String = "protected_file_patterns"

  /** The carve-out from the protected-file list. */
  val UnprotectedFilePatterns: String = "unprotected_file_patterns"

  /** Whether repository administrators are bound by the rule. */
  val ApplyToAdmins: String = "apply_to_admins"

  /** When the rule was created. Response-only; neither request model declares it. */
  val CreatedAt: String = "created_at"

  /** When the rule was last changed. Response-only; neither request model declares it. */
  val UpdatedAt: String = "updated_at"
