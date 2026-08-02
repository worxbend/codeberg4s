package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by the two identifiers in this group that Forgejo expresses as a positive integer.
  *
  * [[TagProtectionId]] and [[DeployKeyId]] are both `int64` on the wire and both end up interpolated into a request
  * path. A number cannot forge a path, so the point is confusion rather than escaping: a tag protection's id, a deploy
  * key's id and the `key_id` of the SSH key behind that deploy key are all `Long`, all plausible values for one
  * another, and all reachable from the same response. Passing one where another belongs gets a `404` that reads like a
  * missing resource rather than like a caller bug — and on an access-control surface, a `404` a caller shrugs at is how
  * a rule nobody deleted is believed to be gone.
  *
  * This duplicates `com.worxbend.codeberg4s.repositories.actions.ActionIds`, `com.worxbend.codeberg4s.issues.NumericId`
  * and `com.worxbend.codeberg4s.pulls.PullIds`, each of which is private to its own group and therefore unreachable
  * from here. `docs/LEDGER.md` already lists that kind of helper under "helpers awaiting promotion"; the right fix is
  * one shared validator in the domain module root, not a widened internal.
  */
private[access] object AccessIds:

  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue")) else Right(value)

/** The identifier of one tag protection rule — the `{id}` of `/repos/{owner}/{repo}/tag_protections/{id}`.
  *
  * Unlike a branch protection rule, which is addressed by its name, a tag protection rule is addressed by this number.
  * That difference is not cosmetic: a number is never reused by the instance, which is why deleting a tag protection is
  * retried and deleting a branch protection is not — see
  * [[com.worxbend.codeberg4s.repositories.access.BranchRuleName]].
  */
opaque type TagProtectionId = Long

object TagProtectionId:

  /** Parses a tag-protection identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"tagProtectionId"` field
    */
  def from(value: Long): Either[ValidationError, TagProtectionId] =
    AccessIds.from("tagProtectionId", value)

  extension (id: TagProtectionId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The identifier of one deploy key — the `{id}` of `/repos/{owner}/{repo}/keys/{id}`.
  *
  * '''This is not the deploy key's `key_id`.''' A `DeployKey` object carries both: `id` addresses the deploy-key grant
  * on this repository, and `key_id` addresses the underlying SSH key row, which is what the listing's `key_id` filter
  * matches. They are different numbers for the same key, and only this one belongs in a path — see
  * [[com.worxbend.codeberg4s.repositories.access.DeployKey.keyId]] and
  * [[com.worxbend.codeberg4s.repositories.access.DeployKeyQuery.keyId]].
  */
opaque type DeployKeyId = Long

object DeployKeyId:

  /** Parses a deploy-key identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"deployKeyId"` field
    */
  def from(value: Long): Either[ValidationError, DeployKeyId] =
    AccessIds.from("deployKeyId", value)

  extension (id: DeployKeyId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
