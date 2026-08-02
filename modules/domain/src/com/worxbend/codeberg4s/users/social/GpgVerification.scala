package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ValidationError

/** An ASCII-armored OpenPGP signature, as `gpg --armor --detach-sign` prints one.
  *
  * Carried by [[VerifyGpgKey]] and by [[CreateGpgKey]]. It is public material — a signature proves possession of a
  * private key without disclosing it — so unlike [[com.worxbend.codeberg4s.auth.ApiToken]] this type does not redact
  * itself and a caller may log one.
  */
opaque type ArmoredSignature = String

object ArmoredSignature:

  /** The field name a rejected value is reported under. */
  private val Field: String = "armoredSignature"

  /** Parses an armored signature.
    *
    * '''Deliberately not trimmed and deliberately permissive about control characters.''' An armored block is
    * multi-line by construction, its line endings are part of what is verified, and it travels in a JSON body where a
    * newline is escaped rather than injected. Only an empty value is rejected, because a signature of nothing proves
    * nothing.
    *
    * The armor headers are not checked either: whether a block is a valid signature is the instance's judgement, and it
    * reports a bad one as `422`. Guessing here would only add a second, less informed rejection.
    *
    * @return
    *   the signature verbatim, or a [[ValidationError]] on the `"armoredSignature"` field
    */
  def from(value: String): Either[ValidationError, ArmoredSignature] =
    if value.isEmpty then Left(ValidationError(Field, "must not be empty"))
    else Right(value)

  extension (signature: ArmoredSignature)

    /** The signature verbatim, ready to be rendered into a request body. */
    def value: String = signature

/** The challenge `GET /user/gpg_key_token` hands out, to be signed and returned by `POST /user/gpg_key_verify`.
  *
  * ==This is not a credential==
  *
  * Nothing can be done with this value except sign it. It authorises no request, it is not accepted in an
  * `Authorization` header, and it is scoped to the account whose token fetched it — Forgejo derives it from that
  * account and the current time. It therefore carries '''no''' redaction discipline: unlike
  * [[com.worxbend.codeberg4s.auth.ApiToken]] it renders itself in full, because a caller debugging a rejected signature
  * needs to see exactly what they signed. The name is Forgejo's, not a description.
  *
  * ==The handshake, and why the order is in the types==
  *
  * Proving possession of a GPG key is two calls, and the second is meaningless without the first:
  *
  *   1. `client.users.keys.verificationToken()` answers a `GpgKeyToken`;
  *   1. the caller signs [[value]] with the private half of the key, out of band;
  *   1. `token.signedWith(keyId, signature)` builds the only [[VerifyGpgKey]] this library can produce;
  *   1. `client.users.keys.verifyGpgKey(command)` sends it.
  *
  * [[VerifyGpgKey]] has no public constructor, so step 3 cannot be reached without step 1. That is the whole reason the
  * two types live in one file: a caller who has not fetched a token has nothing to call.
  *
  * '''Derived from `spec/swagger.v1.json`''': the endpoint declares `produces: text/plain` and the `APIString`
  * response, so the body is the token itself and not a JSON document. No golden capture exists — the path needs a
  * token.
  */
opaque type GpgKeyToken = String

object GpgKeyToken:

  /** The field name a rejected value is reported under. */
  private val Field: String = "gpgKeyToken"

  /** Parses a verification token.
    *
    * Trims surrounding whitespace, because the endpoint answers `text/plain` and a body that arrived with a trailing
    * newline is the same token as one that did not. Rejects an empty or blank value: a blank challenge cannot be
    * signed, so decoding one is a failure worth reporting rather than a token worth carrying.
    *
    * @return
    *   the trimmed token, or a [[ValidationError]] on the `"gpgKeyToken"` field
    */
  def from(value: String): Either[ValidationError, GpgKeyToken] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else Right(trimmed)

  extension (token: GpgKeyToken)

    /** The challenge text, which is exactly what has to be signed.
      *
      * Sign these bytes and nothing else — not a trimmed copy, not a re-encoded one. Forgejo compares the signature
      * against the token it issued.
      */
    def value: String = token

    /** The verification request that claims `keyId` signed this token.
      *
      * The only way to build a [[VerifyGpgKey]]. See the type note for why.
      *
      * @param keyId
      *   the OpenPGP identifier of the key that produced `signature`, which Forgejo matches against the account's
      *   registered keys
      * @param signature
      *   the armored detached signature over [[value]]
      */
    def signedWith(keyId: OpenPgpKeyId, signature: ArmoredSignature): VerifyGpgKey =
      VerifyGpgKey(keyId, signature)

/** The body of `POST /user/gpg_key_verify` — a claim that a named key signed a verification token.
  *
  * '''Constructed only by [[GpgKeyToken.signedWith]].''' The constructor, `apply` and `copy` are all private to this
  * group, so a caller cannot assemble one without having fetched a token first; see [[GpgKeyToken]] for the handshake
  * that ordering encodes.
  *
  * '''Derived from `spec/swagger.v1.json`'s `VerifyGPGKeyOption`''', whose only declared `required` property is
  * `key_id`. The signature is required here anyway, because a verification request without one asks the instance to
  * verify nothing and can only answer `422`.
  *
  * @param keyId
  *   the OpenPGP identifier of the signing key
  * @param armoredSignature
  *   the detached signature over the token's text
  */
final case class VerifyGpgKey private[social] (keyId: OpenPgpKeyId, armoredSignature: ArmoredSignature)
