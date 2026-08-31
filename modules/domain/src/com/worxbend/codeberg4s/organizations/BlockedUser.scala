package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

import java.time.Instant

/** The identifier of one block record — the `block_id` of Forgejo's `BlockedUser`.
  *
  * A database row id for the block itself, not for the blocked account. Nothing in the API takes it as a path segment:
  * a block is lifted by naming the account again, through [[OrganizationApi.unblockUser]]. It exists as a type anyway
  * because it is the only thing on [[BlockedUser]] that distinguishes two entries, and because a bare `Long` in a model
  * whose other field is a timestamp invites being read as a user id — which it is not.
  */
opaque type BlockId = Long

object BlockId:

  /** Parses a block identifier.
    *
    * Rejects anything below `1`: Forgejo's identifiers are database row ids and start at one.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"blockId"` field
    */
  def from(value: Long): Either[ValidationError, BlockId] =
    PositiveId.from("blockId", value)

  extension (id: BlockId)

    /** The identifier as a `Long`. */
    def value: Long = id

/** One entry of an organisation's block list — Forgejo's `BlockedUser`, as `GET /orgs/{org}/list_blocked` reports it.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The route needs a token and
  * `modules/codec/test/resources/golden` was harvested anonymously, so no fixture backs this shape.
  *
  * ==It does not say who was blocked, and that is the model's own shortcoming==
  *
  * `BlockedUser` declares exactly two properties — `block_id` and `created_at` — and neither one names an account. The
  * type is called `BlockedUser` and carries no user. This library reports what the endpoint sends rather than inventing
  * a login for it: correlating a block with an account is not possible from this payload alone on the pinned spec, and
  * a field guessed at here would be a field that quietly disagreed with a future Forgejo release. If a capture ever
  * shows an instance sending more, the capture wins and this model grows.
  *
  * Practically, that makes the listing useful for counting and auditing timestamps, and [[OrganizationApi.blockUser]] /
  * [[OrganizationApi.unblockUser]] the operations that actually name people.
  *
  * @param blockId
  *   the block record's identifier; see [[BlockId]]
  * @param createdAt
  *   when the block was recorded, absent when the instance sent no timestamp or the Go zero-time sentinel
  */
final case class BlockedUser private[codeberg4s] (blockId: BlockId, createdAt: Option[Instant])
