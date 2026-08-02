package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ValidationError

import java.time.Instant

/** An OpenPGP long key id — the value Forgejo sends as `key_id` and `primary_key_id`.
  *
  * This is the key's own identifier, the sixteen hexadecimal digits `gpg --list-keys --keyid-format LONG` prints, and
  * it is what [[VerifyGpgKey]] names when it claims a signature. It is '''not''' [[GpgKeyId]], which is a database row
  * number local to one instance and is what `/user/gpg_keys/{id}` addresses.
  *
  * Values are validated as URI path segments even though no endpoint currently interpolates one, because the value
  * travels in a request body that Forgejo matches against stored keys and a control character in it has no meaning.
  */
opaque type OpenPgpKeyId = String

object OpenPgpKeyId:

  /** The field name a rejected value is reported under. */
  private val Field: String = "openPgpKeyId"

  /** Parses an OpenPGP key id.
    *
    * Trims surrounding whitespace, because the value is routinely pasted out of `gpg` output. Rejects an empty or blank
    * value and any value containing a control character.
    *
    * '''Deliberately not checked for hexadecimal shape.''' Forgejo accepts a short id, a long id and a full
    * fingerprint, and a future release may accept more; rejecting a spelling this library has not measured would turn a
    * working call into a client-side failure. What is rejected here is only what cannot travel.
    *
    * @return
    *   the trimmed identifier, or a [[ValidationError]] on the `"openPgpKeyId"` field
    */
  def from(value: String): Either[ValidationError, OpenPgpKeyId] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else Right(trimmed)

  extension (id: OpenPgpKeyId)

    /** The identifier as a string. */
    def value: String = id

/** One email address attached to a GPG key, and whether the instance considers it proven.
  *
  * '''Derived from `spec/swagger.v1.json`'s `GPGKeyEmail`, not from a captured response''' — every GPG endpoint in this
  * group needs a token and the golden harvest was anonymous.
  *
  * A key can carry an address the account does not own; `isVerified` is Forgejo's answer to whether the address is also
  * a confirmed address of the account holder, and an unverified address means commits signed with that identity are not
  * attributed.
  *
  * @param email
  *   the address as the key's user id declares it
  * @param isVerified
  *   whether the instance has confirmed the address belongs to the account. Absent on the wire reads as `false`, the
  *   answer that claims the least
  */
final case class GpgKeyEmail(email: String, isVerified: Boolean)

/** A GPG key an account has registered, used to verify commit and tag signatures.
  *
  * '''Derived from `spec/swagger.v1.json`'s `GPGKey`, not from a captured response.''' `/user/gpg_keys` answers `401`
  * without a token and `/users/{username}/gpg_keys` was not among the 61 anonymous captures, so the field set below is
  * the spec's property list read literally. `docs/HAZARDS.md` §1 is why everything but [[id]] is optional or defaulted:
  * the spec declares no `required` list on any response model, so it is evidence of which keys may appear and of
  * nothing else.
  *
  * ==Nothing here is a secret==
  *
  * [[publicKey]] is the armored '''public''' half. The private half never leaves the account holder's machine and never
  * appears in this API, so no field of this model needs redacting before it is logged or displayed — exactly as for
  * [[com.worxbend.codeberg4s.users.PublicKey]].
  *
  * ==Subkeys are the same model, one level down==
  *
  * Forgejo nests a key's subkeys under `subkeys`, each a full `GPGKey` object. They are modelled as [[GpgKey]] values
  * rather than a reduced type, because that is what the wire sends; a subkey's own `subkeys` is empty in practice, and
  * nothing here depends on that being true.
  *
  * @param id
  *   the instance-local row identifier, which is what `/user/gpg_keys/{id}` addresses
  * @param keyId
  *   the key's own OpenPGP identifier, absent when the instance sent none
  * @param primaryKeyId
  *   for a subkey, the identifier of the key it belongs to; absent on a primary key
  * @param publicKey
  *   the armored public key block, as `gpg --armor --export` prints it
  * @param emails
  *   the addresses the key claims, each with its verification state. Empty when the instance sent none
  * @param subkeys
  *   the key's subkeys, each a key in its own right. Empty when the instance sent none
  * @param canSign
  *   whether the key may create signatures
  * @param canEncryptComms
  *   whether the key may encrypt communications
  * @param canEncryptStorage
  *   whether the key may encrypt stored data
  * @param canCertify
  *   whether the key may certify other keys
  * @param isVerified
  *   whether the account holder proved possession of the private half through the handshake [[GpgKeyToken]] starts
  * @param createdAt
  *   when the key was registered with the instance
  * @param expiresAt
  *   when the key expires, absent for a key that does not — Forgejo spells "never" as the Go zero time, which
  *   `com.worxbend.codeberg4s.codec.Timestamps` folds into absence
  */
final case class GpgKey(
    id: GpgKeyId,
    keyId: Option[OpenPgpKeyId],
    primaryKeyId: Option[OpenPgpKeyId],
    publicKey: Option[String],
    emails: Vector[GpgKeyEmail],
    subkeys: Vector[GpgKey],
    canSign: Boolean,
    canEncryptComms: Boolean,
    canEncryptStorage: Boolean,
    canCertify: Boolean,
    isVerified: Boolean,
    createdAt: Option[Instant],
    expiresAt: Option[Instant],
)
