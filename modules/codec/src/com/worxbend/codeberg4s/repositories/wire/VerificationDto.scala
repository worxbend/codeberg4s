package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.repositories.CommitVerification

/** Forgejo's `PayloadCommitVerification` — the `verification` object on a commit.
  *
  * `signer` is `null` on every commit in `golden/repository/commits-list.json` and a populated identity on
  * `golden/repository/branches-list.json`, so the field is optional for measured reasons rather than defensive ones.
  *
  * @param verified
  *   the `verified` key
  * @param reason
  *   the `reason` key
  * @param signature
  *   the `signature` key — the armoured signature block
  * @param signer
  *   the `signer` key
  * @param payload
  *   the `payload` key — the signed commit object, verbatim
  */
final case class VerificationDto(
    verified: Option[Boolean],
    reason: Option[String],
    signature: Option[String],
    signer: Option[GitIdentityDto],
    payload: Option[String],
):

  /** Converts to the domain. Cannot fail: an absent `verified` is an unverified commit, which is the safe reading and
    * the one Forgejo itself uses when it omits the object.
    */
  def toDomain: CommitVerification =
    CommitVerification(
      isVerified = verified.getOrElse(false),
      reason     = reason,
      signature  = signature,
      signer     = signer.map(_.toDomain),
      payload    = payload,
    )

object VerificationDto:

  /** Reads a `verification` object. */
  given JsonDecoder[VerificationDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the commit DTOs that embed this one. */
  def fromFields(fields: JsonFields): VerificationDto =
    VerificationDto(
      verified  = fields.boolean("verified"),
      reason    = fields.text("reason"),
      signature = fields.text("signature"),
      signer    = fields.nested("signer").map(GitIdentityDto.fromFields),
      payload   = fields.text("payload"),
    )
