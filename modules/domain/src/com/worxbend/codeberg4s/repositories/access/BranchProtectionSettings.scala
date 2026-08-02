package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.users.Username

/** How many approving reviews a pull request needs before a protected branch will accept it.
  *
  * Zero is a legal and meaningful value — "reviews are tracked but none is required" — so, unlike every identifier in
  * this library, this type accepts it. What it rejects is a negative, which Forgejo answers `422` to and which no
  * caller ever means.
  */
opaque type ApprovalCount = Long

object ApprovalCount:

  /** No approvals required. */
  val None: ApprovalCount = 0L

  /** Parses an approval count. Rejects a negative; accepts zero.
    *
    * @return
    *   the count, or a [[ValidationError]] on the `"requiredApprovals"` field
    */
  def from(value: Long): Either[ValidationError, ApprovalCount] =
    if value < 0L then Left(ValidationError("requiredApprovals", "must not be negative")) else Right(value)

  extension (count: ApprovalCount)

    /** The count as a `Long`, ready to be rendered into a request body. */
    def value: Long = count

/** The twenty-three tunables a branch protection rule has, as both creating and editing one accept them.
  *
  * ==Absent means "do not send", never "off"==
  *
  * Every field is an `Option`, and only the ones a caller set are rendered into a request body. On a `POST` that means
  * Forgejo applies its own defaults to the rest; on the `PATCH` behind [[RepositoryAccessApi.editBranchProtection]] it
  * means the rest are left exactly as they are. Modelling an unset field as `false` instead would turn every partial
  * edit into a silent reset of every switch the caller did not mention — a rule that still exists and no longer
  * protects anything, which is the single worst outcome this group can produce. [[BranchProtectionSettings.Unchanged]]
  * is the empty bag, and it renders to `{}`.
  *
  * ==Whitelisted usernames are validated; whitelisted teams are not==
  *
  * A username goes in as [[com.worxbend.codeberg4s.users.Username]], which rejects a blank or control-bearing value. A
  * whitelist entry that matches no account is not an error to Forgejo — it is simply an entry that never grants
  * anything — so a rule built from a stray `""` would look configured and protect nothing. Team names stay `String`,
  * because a team name is scoped to an organisation and this library has no way to check one; see [[TeamName]] for the
  * same distinction on the path side.
  *
  * A value '''read back''' is a plain `String` on [[BranchProtection]], for the reason
  * `com.worxbend.codeberg4s.repositories.actions.RunnerLabel` states: dropping a whitelist entry the instance really
  * holds, because this library disliked its spelling, would cost the caller data.
  *
  * ==Field names==
  *
  * The wire spelling of each field appears exactly once, in
  * `com.worxbend.codeberg4s.repositories.access.wire.BranchProtectionWire`, per rule 4 of
  * `com.worxbend.codeberg4s.codec.WireConventions`. Note in particular that the approvals whitelist is
  * `approvals_whitelist_username`, '''singular''', where the push and merge whitelists are plural.
  *
  * @param enablePush
  *   whether anyone may push to a matching branch at all. With this off the branch is merge-only
  * @param enablePushWhitelist
  *   whether [[pushWhitelistUsernames]], [[pushWhitelistTeams]] and [[pushWhitelistDeployKeys]] are consulted. Setting
  *   the lists without setting this leaves them inert
  * @param enableMergeWhitelist
  *   the same switch for [[mergeWhitelistUsernames]] and [[mergeWhitelistTeams]]
  * @param enableStatusCheck
  *   whether [[statusCheckContexts]] must all report success before a merge is allowed
  * @param enableApprovalsWhitelist
  *   whether only [[approvalsWhitelistUsernames]] and [[approvalsWhitelistTeams]] count towards [[requiredApprovals]]
  * @param blockOnRejectedReviews
  *   whether one "request changes" review blocks the merge outright
  * @param blockOnOfficialReviewRequests
  *   whether an outstanding review request from someone whose review counts blocks the merge
  * @param blockOnOutdatedBranch
  *   whether a pull request whose head is behind the base must be updated before merging
  * @param dismissStaleApprovals
  *   whether a new push discards approvals given to the previous head
  * @param ignoreStaleApprovals
  *   whether approvals given to a previous head stop counting towards [[requiredApprovals]] without being discarded
  * @param requireSignedCommits
  *   whether every commit reaching a matching branch must carry a signature the instance trusts
  * @param protectedFilePatterns
  *   files nobody may change on a matching branch, in Forgejo's own semicolon-separated glob spelling
  * @param unprotectedFilePatterns
  *   the carve-out from [[protectedFilePatterns]], in the same spelling
  * @param applyToAdmins
  *   whether repository administrators are bound by the rule as well. Off, the rule is advisory for anyone who can turn
  *   it off
  */
final case class BranchProtectionSettings(
    enablePush: Option[Boolean],
    enablePushWhitelist: Option[Boolean],
    pushWhitelistUsernames: Option[Vector[Username]],
    pushWhitelistTeams: Option[Vector[String]],
    pushWhitelistDeployKeys: Option[Boolean],
    enableMergeWhitelist: Option[Boolean],
    mergeWhitelistUsernames: Option[Vector[Username]],
    mergeWhitelistTeams: Option[Vector[String]],
    enableStatusCheck: Option[Boolean],
    statusCheckContexts: Option[Vector[String]],
    requiredApprovals: Option[ApprovalCount],
    enableApprovalsWhitelist: Option[Boolean],
    approvalsWhitelistUsernames: Option[Vector[Username]],
    approvalsWhitelistTeams: Option[Vector[String]],
    blockOnRejectedReviews: Option[Boolean],
    blockOnOfficialReviewRequests: Option[Boolean],
    blockOnOutdatedBranch: Option[Boolean],
    dismissStaleApprovals: Option[Boolean],
    ignoreStaleApprovals: Option[Boolean],
    requireSignedCommits: Option[Boolean],
    protectedFilePatterns: Option[String],
    unprotectedFilePatterns: Option[String],
    applyToAdmins: Option[Boolean],
):

  /** States whether pushing to a matching branch is permitted at all. */
  def pushing(allowed: Boolean): BranchProtectionSettings = copy(enablePush = Some(allowed))

  /** States whether the push whitelist is consulted. */
  def pushWhitelisting(enabled: Boolean): BranchProtectionSettings = copy(enablePushWhitelist = Some(enabled))

  /** Replaces the accounts on the push whitelist. */
  def pushWhitelistedTo(usernames: Vector[Username]): BranchProtectionSettings =
    copy(pushWhitelistUsernames = Some(usernames))

  /** Replaces the teams on the push whitelist. */
  def pushWhitelistedToTeams(teams: Vector[String]): BranchProtectionSettings =
    copy(pushWhitelistTeams = Some(teams))

  /** States whether deploy keys with write access bypass the push whitelist. */
  def pushWhitelistingDeployKeys(enabled: Boolean): BranchProtectionSettings =
    copy(pushWhitelistDeployKeys = Some(enabled))

  /** States whether the merge whitelist is consulted. */
  def mergeWhitelisting(enabled: Boolean): BranchProtectionSettings = copy(enableMergeWhitelist = Some(enabled))

  /** Replaces the accounts on the merge whitelist. */
  def mergeWhitelistedTo(usernames: Vector[Username]): BranchProtectionSettings =
    copy(mergeWhitelistUsernames = Some(usernames))

  /** Replaces the teams on the merge whitelist. */
  def mergeWhitelistedToTeams(teams: Vector[String]): BranchProtectionSettings =
    copy(mergeWhitelistTeams = Some(teams))

  /** States whether status checks are required before a merge. */
  def statusChecking(enabled: Boolean): BranchProtectionSettings = copy(enableStatusCheck = Some(enabled))

  /** Replaces the status check contexts that must report success. */
  def statusCheckingContexts(contexts: Vector[String]): BranchProtectionSettings =
    copy(statusCheckContexts = Some(contexts))

  /** Sets how many approving reviews a pull request needs. */
  def requiringApprovals(count: ApprovalCount): BranchProtectionSettings = copy(requiredApprovals = Some(count))

  /** States whether only whitelisted reviewers' approvals count. */
  def approvalWhitelisting(enabled: Boolean): BranchProtectionSettings =
    copy(enableApprovalsWhitelist = Some(enabled))

  /** Replaces the accounts whose approvals count. */
  def approvalWhitelistedTo(usernames: Vector[Username]): BranchProtectionSettings =
    copy(approvalsWhitelistUsernames = Some(usernames))

  /** Replaces the teams whose approvals count. */
  def approvalWhitelistedToTeams(teams: Vector[String]): BranchProtectionSettings =
    copy(approvalsWhitelistTeams = Some(teams))

  /** States whether one rejecting review blocks the merge. */
  def blockingOnRejectedReviews(enabled: Boolean): BranchProtectionSettings =
    copy(blockOnRejectedReviews = Some(enabled))

  /** States whether an outstanding official review request blocks the merge. */
  def blockingOnOfficialReviewRequests(enabled: Boolean): BranchProtectionSettings =
    copy(blockOnOfficialReviewRequests = Some(enabled))

  /** States whether a branch behind its base must be updated before merging. */
  def blockingOnOutdatedBranch(enabled: Boolean): BranchProtectionSettings =
    copy(blockOnOutdatedBranch = Some(enabled))

  /** States whether a new push discards existing approvals. */
  def dismissingStaleApprovals(enabled: Boolean): BranchProtectionSettings =
    copy(dismissStaleApprovals = Some(enabled))

  /** States whether approvals of a superseded head stop counting. */
  def ignoringStaleApprovals(enabled: Boolean): BranchProtectionSettings =
    copy(ignoreStaleApprovals = Some(enabled))

  /** States whether every commit must be signed. */
  def requiringSignedCommits(enabled: Boolean): BranchProtectionSettings =
    copy(requireSignedCommits = Some(enabled))

  /** Replaces the protected-file glob list, in Forgejo's semicolon-separated spelling. */
  def protectingFiles(patterns: String): BranchProtectionSettings = copy(protectedFilePatterns = Some(patterns))

  /** Replaces the carve-out from the protected-file list, in the same spelling. */
  def unprotectingFiles(patterns: String): BranchProtectionSettings = copy(unprotectedFilePatterns = Some(patterns))

  /** States whether repository administrators are bound by the rule. */
  def applyingToAdmins(enabled: Boolean): BranchProtectionSettings = copy(applyToAdmins = Some(enabled))

object BranchProtectionSettings:

  /** Nothing stated: on a create, every setting is Forgejo's default; on an edit, every setting is left alone.
    *
    * {{{
    * BranchProtectionSettings.Unchanged
    *   .requiringSignedCommits(true)
    *   .requiringApprovals(approvals)
    *   .applyingToAdmins(true)
    * }}}
    */
  val Unchanged: BranchProtectionSettings = BranchProtectionSettings(
    enablePush                    = None,
    enablePushWhitelist           = None,
    pushWhitelistUsernames        = None,
    pushWhitelistTeams            = None,
    pushWhitelistDeployKeys       = None,
    enableMergeWhitelist          = None,
    mergeWhitelistUsernames       = None,
    mergeWhitelistTeams           = None,
    enableStatusCheck             = None,
    statusCheckContexts           = None,
    requiredApprovals             = None,
    enableApprovalsWhitelist      = None,
    approvalsWhitelistUsernames   = None,
    approvalsWhitelistTeams       = None,
    blockOnRejectedReviews        = None,
    blockOnOfficialReviewRequests = None,
    blockOnOutdatedBranch         = None,
    dismissStaleApprovals         = None,
    ignoreStaleApprovals          = None,
    requireSignedCommits          = None,
    protectedFilePatterns         = None,
    unprotectedFilePatterns       = None,
    applyToAdmins                 = None,
  )
