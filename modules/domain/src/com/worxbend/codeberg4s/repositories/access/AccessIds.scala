package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

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
    PositiveId.from("tagProtectionId", value)

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
    PositiveId.from("deployKeyId", value)

  extension (id: DeployKeyId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
