package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.Branch
import com.worxbend.codeberg4s.repositories.BranchName

/** Forgejo's `Branch` model, field for field.
  *
  * Every key on `golden/repository/branch-single.json` and on the three elements of
  * `golden/repository/branches-list.json` is represented. Two of those branches are named `renovate/…` and `v16.0/…`,
  * which is the evidence behind [[com.worxbend.codeberg4s.repositories.BranchName]] accepting slashes where
  * [[com.worxbend.codeberg4s.repositories.RepoName]] rejects them.
  *
  * @param name
  *   the `name` key
  * @param commit
  *   the `commit` key: the branch tip
  * @param isProtected
  *   the `protected` key, renamed because `protected` is a Scala keyword
  * @param requiredApprovals
  *   the `required_approvals` key
  * @param enableStatusCheck
  *   the `enable_status_check` key
  * @param statusCheckContexts
  *   the `status_check_contexts` key; empty when absent, `null`, or an empty array, which is what an unprotected branch
  *   sends
  * @param userCanPush
  *   the `user_can_push` key, answered for the credentials that made the request
  * @param userCanMerge
  *   the `user_can_merge` key, same caveat
  * @param effectiveBranchProtectionName
  *   the `effective_branch_protection_name` key; Forgejo sends `""` when no rule matched
  */
final case class BranchDto(
    name: Option[String],
    commit: Option[PayloadCommitDto],
    isProtected: Option[Boolean],
    requiredApprovals: Option[Long],
    enableStatusCheck: Option[Boolean],
    statusCheckContexts: Vector[String],
    userCanPush: Option[Boolean],
    userCanMerge: Option[Boolean],
    effectiveBranchProtectionName: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required: `name`, which additionally goes through
    * [[com.worxbend.codeberg4s.repositories.BranchName.from]], and `commit` — a branch that does not say where it
    * points is not something a caller can do anything with. A failure inside the commit is reported at `commit`'s own
    * path.
    *
    * Absent flags become `false` and an absent `required_approvals` becomes `0`, which is what an unprotected branch
    * means.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Branch] =
    for
      branchName <- Wire.validated(at, "name", name)(BranchName.from)
      commitDto  <- Wire.required(at, "commit", commit)
      tip        <- commitDto.toDomainAt(at.field("commit"))
    yield Branch(
      name                          = branchName,
      commit                        = tip,
      isProtected                   = isProtected.getOrElse(false),
      requiredApprovals             = requiredApprovals.getOrElse(0L),
      statusCheckEnabled            = enableStatusCheck.getOrElse(false),
      statusCheckContexts           = statusCheckContexts,
      userCanPush                   = userCanPush.getOrElse(false),
      userCanMerge                  = userCanMerge.getOrElse(false),
      effectiveBranchProtectionName = effectiveBranchProtectionName,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Branch] =
    toDomainAt(JsonPath.Root)

object BranchDto:

  /** Reads a `Branch` object. */
  given JsonDecoder[BranchDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): BranchDto =
    BranchDto(
      name                          = fields.text("name"),
      commit                        = fields.nested("commit").map(PayloadCommitDto.fromFields),
      isProtected                   = fields.boolean("protected"),
      requiredApprovals             = fields.number("required_approvals"),
      enableStatusCheck             = fields.boolean("enable_status_check"),
      statusCheckContexts           = fields.texts("status_check_contexts"),
      userCanPush                   = fields.boolean("user_can_push"),
      userCanMerge                  = fields.boolean("user_can_merge"),
      effectiveBranchProtectionName = fields.text("effective_branch_protection_name"),
    )
