package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.access.BranchProtection
import com.worxbend.codeberg4s.repositories.wire.Elements

/** Forgejo's `BranchProtection` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The harvest behind
  * `modules/codec/test/resources/golden` was anonymous and `GET /repos/{owner}/{repo}/branch_protections` requires a
  * token, so no fixture exists for this model or for any other in this group. The twenty-seven fields below are the
  * twenty-seven properties of the pinned spec's definition; the nullability treatment is the conservative one
  * `docs/HAZARDS.md` §1 mandates for the whole API, which is that absent, `null` and wrong-kind are one case. Where a
  * shape is asserted in a test, the payload was written by hand to match that definition — it is not evidence that
  * Forgejo sends exactly this.
  *
  * Every wire spelling is read from [[BranchProtectionWire]] rather than written here, because the same names are also
  * rendered by two request models; see that object for why three copies would be one too many.
  *
  * Timestamps stay as raw strings; [[com.worxbend.codeberg4s.codec.Timestamps]] normalises them, zero-time sentinel
  * included, during conversion.
  */
final case class BranchProtectionDto(
    ruleName: Option[String],
    branchName: Option[String],
    enablePush: Option[Boolean],
    enablePushWhitelist: Option[Boolean],
    pushWhitelistUsernames: Vector[String],
    pushWhitelistTeams: Vector[String],
    pushWhitelistDeployKeys: Option[Boolean],
    enableMergeWhitelist: Option[Boolean],
    mergeWhitelistUsernames: Vector[String],
    mergeWhitelistTeams: Vector[String],
    enableStatusCheck: Option[Boolean],
    statusCheckContexts: Vector[String],
    requiredApprovals: Option[Long],
    enableApprovalsWhitelist: Option[Boolean],
    approvalsWhitelistUsernames: Vector[String],
    approvalsWhitelistTeams: Vector[String],
    blockOnRejectedReviews: Option[Boolean],
    blockOnOfficialReviewRequests: Option[Boolean],
    blockOnOutdatedBranch: Option[Boolean],
    dismissStaleApprovals: Option[Boolean],
    ignoreStaleApprovals: Option[Boolean],
    requireSignedCommits: Option[Boolean],
    protectedFilePatterns: Option[String],
    unprotectedFilePatterns: Option[String],
    applyToAdmins: Option[Boolean],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * '''One field is required, and it has a fallback.''' A rule with no name matches nothing and can be neither read
    * back nor removed, so the conversion fails without one — at `$.rule_name`, even when the fallback was what was
    * missing. The fallback is the deprecated `branch_name`: Forgejo renamed the property, and an older instance sends
    * only the old spelling. Preferring `rule_name` and falling back keeps a rule from an old deployment decodable
    * without inventing anything, and `branch_name` is still carried through to
    * [[com.worxbend.codeberg4s.repositories.access.BranchProtection.legacyBranchName]] so nothing is lost.
    *
    * '''Everything else is defaulted, never failed.''' An absent flag becomes `false` and an absent count becomes `0`;
    * see [[com.worxbend.codeberg4s.repositories.access.BranchProtection]] for why that direction was chosen. Arrays
    * that arrive as `null` are already empty vectors by the time they get here, per
    * [[com.worxbend.codeberg4s.codec.JsonFields.texts]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, BranchProtection] =
    Wire
      .required(at, BranchProtectionWire.RuleName, ruleName.orElse(branchName))
      .map(name =>
        BranchProtection(
          ruleName                      = name,
          legacyBranchName              = branchName,
          enablePush                    = enablePush.getOrElse(false),
          enablePushWhitelist           = enablePushWhitelist.getOrElse(false),
          pushWhitelistUsernames        = pushWhitelistUsernames,
          pushWhitelistTeams            = pushWhitelistTeams,
          pushWhitelistDeployKeys       = pushWhitelistDeployKeys.getOrElse(false),
          enableMergeWhitelist          = enableMergeWhitelist.getOrElse(false),
          mergeWhitelistUsernames       = mergeWhitelistUsernames,
          mergeWhitelistTeams           = mergeWhitelistTeams,
          enableStatusCheck             = enableStatusCheck.getOrElse(false),
          statusCheckContexts           = statusCheckContexts,
          requiredApprovals             = requiredApprovals.getOrElse(0L),
          enableApprovalsWhitelist      = enableApprovalsWhitelist.getOrElse(false),
          approvalsWhitelistUsernames   = approvalsWhitelistUsernames,
          approvalsWhitelistTeams       = approvalsWhitelistTeams,
          blockOnRejectedReviews        = blockOnRejectedReviews.getOrElse(false),
          blockOnOfficialReviewRequests = blockOnOfficialReviewRequests.getOrElse(false),
          blockOnOutdatedBranch         = blockOnOutdatedBranch.getOrElse(false),
          dismissStaleApprovals         = dismissStaleApprovals.getOrElse(false),
          ignoreStaleApprovals          = ignoreStaleApprovals.getOrElse(false),
          requireSignedCommits          = requireSignedCommits.getOrElse(false),
          protectedFilePatterns         = protectedFilePatterns,
          unprotectedFilePatterns       = unprotectedFilePatterns,
          applyToAdmins                 = applyToAdmins.getOrElse(false),
          createdAt                     = Timestamps.parseOptional(createdAt),
          updatedAt                     = Timestamps.parseOptional(updatedAt),
        )
      )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, BranchProtection] =
    toDomainAt(JsonPath.Root)

object BranchProtectionDto:

  /** Reads a `BranchProtection` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[BranchProtectionDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): BranchProtectionDto =
    BranchProtectionDto(
      ruleName                      = fields.text(BranchProtectionWire.RuleName),
      branchName                    = fields.text(BranchProtectionWire.BranchName),
      enablePush                    = fields.boolean(BranchProtectionWire.EnablePush),
      enablePushWhitelist           = fields.boolean(BranchProtectionWire.EnablePushWhitelist),
      pushWhitelistUsernames        = fields.texts(BranchProtectionWire.PushWhitelistUsernames),
      pushWhitelistTeams            = fields.texts(BranchProtectionWire.PushWhitelistTeams),
      pushWhitelistDeployKeys       = fields.boolean(BranchProtectionWire.PushWhitelistDeployKeys),
      enableMergeWhitelist          = fields.boolean(BranchProtectionWire.EnableMergeWhitelist),
      mergeWhitelistUsernames       = fields.texts(BranchProtectionWire.MergeWhitelistUsernames),
      mergeWhitelistTeams           = fields.texts(BranchProtectionWire.MergeWhitelistTeams),
      enableStatusCheck             = fields.boolean(BranchProtectionWire.EnableStatusCheck),
      statusCheckContexts           = fields.texts(BranchProtectionWire.StatusCheckContexts),
      requiredApprovals             = fields.number(BranchProtectionWire.RequiredApprovals),
      enableApprovalsWhitelist      = fields.boolean(BranchProtectionWire.EnableApprovalsWhitelist),
      approvalsWhitelistUsernames   = fields.texts(BranchProtectionWire.ApprovalsWhitelistUsernames),
      approvalsWhitelistTeams       = fields.texts(BranchProtectionWire.ApprovalsWhitelistTeams),
      blockOnRejectedReviews        = fields.boolean(BranchProtectionWire.BlockOnRejectedReviews),
      blockOnOfficialReviewRequests = fields.boolean(BranchProtectionWire.BlockOnOfficialReviewRequests),
      blockOnOutdatedBranch         = fields.boolean(BranchProtectionWire.BlockOnOutdatedBranch),
      dismissStaleApprovals         = fields.boolean(BranchProtectionWire.DismissStaleApprovals),
      ignoreStaleApprovals          = fields.boolean(BranchProtectionWire.IgnoreStaleApprovals),
      requireSignedCommits          = fields.boolean(BranchProtectionWire.RequireSignedCommits),
      protectedFilePatterns         = fields.text(BranchProtectionWire.ProtectedFilePatterns),
      unprotectedFilePatterns       = fields.text(BranchProtectionWire.UnprotectedFilePatterns),
      applyToAdmins                 = fields.boolean(BranchProtectionWire.ApplyToAdmins),
      createdAt                     = fields.text(BranchProtectionWire.CreatedAt),
      updatedAt                     = fields.text(BranchProtectionWire.UpdatedAt),
    )

  /** Converts a decoded array of rules, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[BranchProtectionDto]): Either[DecodeFailure, Vector[BranchProtection]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
