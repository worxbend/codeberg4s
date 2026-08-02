package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier in this group that Forgejo expresses as a positive integer.
  *
  * [[SshKeyId]], [[GpgKeyId]], [[AccessTokenId]] and [[BlockId]] are all `int64` on the wire and three of the four end
  * up interpolated into a request path. A number cannot forge a path, so the point is confusion rather than escaping: a
  * key's row id, a token's row id and a block's row id are all `Long`, all plausible values for one another, and all
  * reachable from responses a caller holds at the same time. Passing one where another belongs gets a `404` that reads
  * like a missing resource rather than like a caller bug.
  *
  * This duplicates `com.worxbend.codeberg4s.repositories.actions.ActionIds`, `com.worxbend.codeberg4s.issues.NumericId`
  * and `com.worxbend.codeberg4s.pulls.PullIds`, each of which is private to its own group and therefore unreachable
  * from here. `docs/LEDGER.md` already lists that kind of helper under "helpers awaiting promotion"; the right fix is
  * one shared validator in the domain module root, not a widened internal.
  */
private[social] object SocialIds:

  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue"))
    else Right(value)

/** The row identifier of one registered SSH key — the `{id}` of `/user/keys/{id}`.
  *
  * [[com.worxbend.codeberg4s.users.PublicKey.id]] is a bare `Long`, because that model predates this group and is
  * shared with `client.users`. Converting is therefore a deliberate step through [[SshKeyId.from]] rather than an
  * implicit widening, which is also what stops a [[GpgKeyId]] read off a GPG key from reaching an SSH-key endpoint.
  */
opaque type SshKeyId = Long

object SshKeyId:

  /** Parses an SSH-key identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"sshKeyId"` field
    */
  def from(value: Long): Either[ValidationError, SshKeyId] =
    SocialIds.from("sshKeyId", value)

  extension (id: SshKeyId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The row identifier of one registered GPG key — the `{id}` of `/user/gpg_keys/{id}`.
  *
  * '''Not''' the OpenPGP key id. Forgejo's `GPGKey` model carries both: `id` is a database row number local to the
  * instance, and `key_id` is the key's own long identifier as OpenPGP defines it. Only the first addresses an endpoint;
  * the second is [[OpenPgpKeyId]] and is what a signature names. Confusing them is the single easiest mistake to make
  * against these endpoints, which is why they are different types.
  */
opaque type GpgKeyId = Long

object GpgKeyId:

  /** Parses a GPG-key row identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"gpgKeyId"` field
    */
  def from(value: Long): Either[ValidationError, GpgKeyId] =
    SocialIds.from("gpgKeyId", value)

  extension (id: GpgKeyId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The row identifier of one access token, as `AccessToken.id` reports it.
  *
  * `DELETE /users/{username}/tokens/{token}` accepts either this or the token's name; see [[AccessTokenRef]] for why
  * the difference decides whether the delete may be retried.
  */
opaque type AccessTokenId = Long

object AccessTokenId:

  /** Parses an access-token identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"accessTokenId"` field
    */
  def from(value: Long): Either[ValidationError, AccessTokenId] =
    SocialIds.from("accessTokenId", value)

  extension (id: AccessTokenId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The identifier of one block relationship — `BlockedUser.block_id`.
  *
  * No endpoint takes it: `/user/block/{username}` and `/user/unblock/{username}` are addressed by account handle, and
  * `/user/list_blocked` is the only place this value appears. It is carried because it is the only field on the wire
  * model that distinguishes two entries — see [[BlockedUser]] for what Forgejo does '''not''' send.
  */
opaque type BlockId = Long

object BlockId:

  /** Parses a block identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"blockId"` field
    */
  def from(value: Long): Either[ValidationError, BlockId] =
    SocialIds.from("blockId", value)

  extension (id: BlockId)

    /** The identifier as a `Long`. */
    def value: Long = id
