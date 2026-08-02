package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.repositories.access.ApprovalCount
import com.worxbend.codeberg4s.repositories.access.BranchProtectionSettings
import com.worxbend.codeberg4s.repositories.access.CreateBranchProtection
import com.worxbend.codeberg4s.repositories.access.EditBranchProtection
import com.worxbend.codeberg4s.users.Username

/** The twenty-three tunables both branch protection request models share, rendered once.
  *
  * `CreateBranchProtectionOption` and `EditBranchProtectionOption` differ by exactly two properties — the create model
  * also declares `rule_name` and the deprecated `branch_name` — so everything else is emitted from here and the two
  * renderers below add only what is theirs. That is not just economy: a switch that reached the create body and not the
  * edit body would be a rule a caller could set and never change.
  *
  * ==Only what the caller stated is emitted==
  *
  * A field the caller left unset is absent from the body, not `false`. On a `PATCH` that is the whole point — Forgejo
  * leaves an absent property alone, and a body naming all twenty-three would reset every switch the caller did not
  * mention. On a `POST` it means Forgejo's own defaults apply, which is what a caller who said nothing asked for.
  *
  * ==An empty array is emitted, and it means "clear this list"==
  *
  * `Some(Vector.empty)` renders as `[]` and not as absence. Emptying a whitelist is a thing a caller does on purpose,
  * and folding it into "no change" would make the tightening of a rule silently do nothing. `None` is how "leave it" is
  * spelled.
  *
  * Every key comes from [[BranchProtectionWire]]; see that object for why, and for the one name that is singular where
  * its neighbours are plural.
  */
private[wire] object BranchProtectionSettingsDto:

  /** The stated tunables as JSON fields, in the order the spec declares them.
    *
    * The order is stable so that a rendered body can be asserted as an exact string — a renderer's product is bytes,
    * and a test that re-parsed them would not notice a key that moved.
    */
  def fields(settings: BranchProtectionSettings): List[(String, ujson.Value)] =
    List(
      settings.enablePush.map(flag(BranchProtectionWire.EnablePush)),
      settings.enablePushWhitelist.map(flag(BranchProtectionWire.EnablePushWhitelist)),
      settings.pushWhitelistUsernames.map(logins(BranchProtectionWire.PushWhitelistUsernames)),
      settings.pushWhitelistTeams.map(texts(BranchProtectionWire.PushWhitelistTeams)),
      settings.pushWhitelistDeployKeys.map(flag(BranchProtectionWire.PushWhitelistDeployKeys)),
      settings.enableMergeWhitelist.map(flag(BranchProtectionWire.EnableMergeWhitelist)),
      settings.mergeWhitelistUsernames.map(logins(BranchProtectionWire.MergeWhitelistUsernames)),
      settings.mergeWhitelistTeams.map(texts(BranchProtectionWire.MergeWhitelistTeams)),
      settings.enableStatusCheck.map(flag(BranchProtectionWire.EnableStatusCheck)),
      settings.statusCheckContexts.map(texts(BranchProtectionWire.StatusCheckContexts)),
      settings.requiredApprovals.map(approvals(BranchProtectionWire.RequiredApprovals)),
      settings.enableApprovalsWhitelist.map(flag(BranchProtectionWire.EnableApprovalsWhitelist)),
      settings.approvalsWhitelistUsernames.map(logins(BranchProtectionWire.ApprovalsWhitelistUsernames)),
      settings.approvalsWhitelistTeams.map(texts(BranchProtectionWire.ApprovalsWhitelistTeams)),
      settings.blockOnRejectedReviews.map(flag(BranchProtectionWire.BlockOnRejectedReviews)),
      settings.blockOnOfficialReviewRequests.map(flag(BranchProtectionWire.BlockOnOfficialReviewRequests)),
      settings.blockOnOutdatedBranch.map(flag(BranchProtectionWire.BlockOnOutdatedBranch)),
      settings.dismissStaleApprovals.map(flag(BranchProtectionWire.DismissStaleApprovals)),
      settings.ignoreStaleApprovals.map(flag(BranchProtectionWire.IgnoreStaleApprovals)),
      settings.requireSignedCommits.map(flag(BranchProtectionWire.RequireSignedCommits)),
      settings.protectedFilePatterns.map(text(BranchProtectionWire.ProtectedFilePatterns)),
      settings.unprotectedFilePatterns.map(text(BranchProtectionWire.UnprotectedFilePatterns)),
      settings.applyToAdmins.map(flag(BranchProtectionWire.ApplyToAdmins)),
    ).flatten

  private def flag(key: String): Boolean => (String, ujson.Value) =
    value => key -> ujson.Bool(value)

  private def approvals(key: String): ApprovalCount => (String, ujson.Value) =
    count => key -> ujson.Num(count.value.toDouble)

  private def text(key: String): String => (String, ujson.Value) =
    value => key -> ujson.Str(value)

  private def texts(key: String): Vector[String] => (String, ujson.Value) =
    values => key -> ujson.Arr.from(values.map(ujson.Str.apply))

  private def logins(key: String): Vector[Username] => (String, ujson.Value) =
    values => key -> ujson.Arr.from(values.map(name => ujson.Str(name.value)))

/** Forgejo's `CreateBranchProtectionOption` request model — the body of
  * `POST /repos/{owner}/{repo}/branch_protections`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds.
  *
  * `rule_name` is always emitted, because a rule without one matches nothing —
  * [[com.worxbend.codeberg4s.repositories.access.CreateBranchProtection.on]] is why it cannot be missing. The
  * deprecated `branch_name` is emitted only when the caller explicitly asked for it; see
  * [[com.worxbend.codeberg4s.repositories.access.CreateBranchProtection.legacyBranchName]]. Everything else comes from
  * [[BranchProtectionSettingsDto]].
  */
private[codeberg4s] object CreateBranchProtectionOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateBranchProtection): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: CreateBranchProtection): List[(String, ujson.Value)] =
    List(
      Some(BranchProtectionWire.RuleName -> ujson.Str(command.ruleName.value)),
      command.legacyBranchName.map(branch => BranchProtectionWire.BranchName -> ujson.Str(branch.value)),
    ).flatten ++ BranchProtectionSettingsDto.fields(command.settings)

/** Forgejo's `EditBranchProtectionOption` request model — the body of
  * `PATCH /repos/{owner}/{repo}/branch_protections/{name}`.
  *
  * The whole body is [[BranchProtectionSettingsDto]] and nothing else: the spec's edit model declares neither
  * `rule_name` nor `branch_name`, so a rule cannot be renamed through this endpoint and this renderer has no way to
  * try.
  *
  * A command that states nothing renders to `{}`. That is deliberate — see
  * [[com.worxbend.codeberg4s.repositories.access.EditBranchProtection.Nothing]] — and it is the reading that cannot
  * accidentally unprotect a branch.
  */
private[codeberg4s] object EditBranchProtectionOptionDto:

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditBranchProtection): String =
    ujson.write(ujson.Obj.from(BranchProtectionSettingsDto.fields(command.settings)))
