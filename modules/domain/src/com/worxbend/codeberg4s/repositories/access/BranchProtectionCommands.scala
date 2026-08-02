package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.repositories.BranchName

/** What `POST /repos/{owner}/{repo}/branch_protections` is told.
  *
  * {{{
  * CreateBranchProtection
  *   .on(ruleName)
  *   .withSettings(BranchProtectionSettings.Unchanged.requiringSignedCommits(true).applyingToAdmins(true))
  * }}}
  *
  * `spec/swagger.v1.json` declares '''no''' required property on `CreateBranchProtectionOption` — consistent with
  * `docs/HAZARDS.md` §1, which measured that the 38 definitions carrying a `required` list are all request models and
  * that this is not one of them. That is not a licence to send `{}`: a rule that matches nothing is not a rule, so
  * [[CreateBranchProtection.on]] demands the name up front and the type has no way to spell a nameless rule.
  *
  * @param ruleName
  *   the glob the new rule matches branches with, sent as `rule_name`
  * @param legacyBranchName
  *   the deprecated `branch_name` property. Forgejo replaced it with `rule_name` and the spec says so in the property's
  *   own description; it is offered because an older instance may still read it, and because a request model this
  *   library renders is a rendering of what the spec declares rather than of what it wishes the spec declared. Typed as
  *   a [[com.worxbend.codeberg4s.repositories.BranchName]] and not a [[BranchRuleName]], because this one really is a
  *   branch: it names a single branch, and it may span segments
  * @param settings
  *   the tunables to state; anything left unset is Forgejo's own default
  */
final case class CreateBranchProtection(
    ruleName: BranchRuleName,
    legacyBranchName: Option[BranchName],
    settings: BranchProtectionSettings,
):

  /** Replaces the settings the rule is created with. */
  def withSettings(values: BranchProtectionSettings): CreateBranchProtection = copy(settings = values)

  /** Also sends the deprecated `branch_name` property; see [[legacyBranchName]]. */
  def alsoNamingBranch(branch: BranchName): CreateBranchProtection = copy(legacyBranchName = Some(branch))

object CreateBranchProtection:

  /** Starts a command from the one thing a rule cannot exist without.
    *
    * Total rather than validated: [[BranchRuleName]] has already rejected everything this could reject.
    */
  def on(ruleName: BranchRuleName): CreateBranchProtection =
    CreateBranchProtection(ruleName = ruleName, legacyBranchName = None, settings = BranchProtectionSettings.Unchanged)

/** What `PATCH /repos/{owner}/{repo}/branch_protections/{name}` is told.
  *
  * `EditBranchProtectionOption` is `CreateBranchProtectionOption` minus `rule_name` and `branch_name`: a rule cannot be
  * renamed through this endpoint, which is why the name lives in the path and nowhere in this type.
  *
  * '''Only what the caller stated is sent.''' See [[BranchProtectionSettings]] for why that matters more here than
  * anywhere else in the library: a body carrying every switch would reset every switch the caller did not mention, and
  * the rule would still be there, still named, and no longer protecting what it did the minute before.
  *
  * @param settings
  *   the tunables to change; anything left unset is left as the instance has it
  */
final case class EditBranchProtection(settings: BranchProtectionSettings):

  /** Replaces the settings this edit states. */
  def withSettings(values: BranchProtectionSettings): EditBranchProtection = copy(settings = values)

object EditBranchProtection:

  /** An edit that changes nothing, to be built on.
    *
    * Sending it as-is is legal and renders to `{}`; Forgejo answers `200` with the rule unchanged. That is a
    * deliberately harmless default rather than a forbidden one — a command builder that could not express "no change"
    * would make every conditional edit a branch in the caller's code.
    */
  val Nothing: EditBranchProtection = EditBranchProtection(BranchProtectionSettings.Unchanged)

  /** An edit stating exactly `settings`. */
  def of(settings: BranchProtectionSettings): EditBranchProtection =
    EditBranchProtection(settings)
