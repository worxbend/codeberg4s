package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ValidationError

/** What `POST /user/keys` needs to register an SSH key.
  *
  * '''Derived from `spec/swagger.v1.json`'s `CreateKeyOption`''', which declares `title` and `key` required and
  * `read_only` optional. No golden capture exists — the path needs a token.
  *
  * Built through [[CreateSshKey.of]] and refined with [[readOnly]], so a value of this type is always a request the
  * instance can act on. The constructor, `apply` and `copy` are private for that reason.
  *
  * ==`read_only` is about pushing, not about reading this object==
  *
  * A read-only key may fetch but not push. It is sent only when the caller asked for it: omitting the key leaves the
  * decision to Forgejo's default, which is a read/write key, and sending `read_only: false` explicitly says the same
  * thing. The two spellings are indistinguishable to the instance, so this model carries a plain `Boolean` and always
  * emits it — the alternative, an `Option[Boolean]` with two spellings for one meaning, buys nothing.
  *
  * @param title
  *   the label the key is listed under, which the account holder chooses
  * @param key
  *   the key material as `git` reads it, for example `ssh-ed25519 AAAA… comment`
  * @param isReadOnly
  *   whether the key may only fetch
  */
final case class CreateSshKey private (title: String, key: String, isReadOnly: Boolean):

  /** The same request, restricted to fetching. */
  def readOnly: CreateSshKey = copy(isReadOnly = true)

  /** The same request, permitted to push. The instance's own default, stated explicitly. */
  def readWrite: CreateSshKey = copy(isReadOnly = false)

object CreateSshKey:

  /** Describes a key to register.
    *
    * Both arguments are trimmed and both must be non-blank: an empty title lists the key under nothing, and empty
    * material registers nothing. Nothing else is checked — whether the material parses as an SSH key, and whether the
    * instance already holds it, are the instance's judgements and it reports them as `422`.
    *
    * @param title
    *   the label to list the key under, reported as the `"title"` field when rejected
    * @param key
    *   the key material, reported as the `"key"` field when rejected
    * @return
    *   a read/write key request, or the first [[ValidationError]]
    */
  def of(title: String, key: String): Either[ValidationError, CreateSshKey] =
    for
      label    <- required("title", title)
      material <- required("key", key)
    yield CreateSshKey(label, material, isReadOnly = false)

  private def required(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError(field, "must not be blank")) else Right(trimmed)

/** What `POST /user/gpg_keys` needs to register a GPG key.
  *
  * '''Derived from `spec/swagger.v1.json`'s `CreateGPGKeyOption`''', which declares `armored_public_key` required and
  * `armored_signature` optional. No golden capture exists — the path needs a token.
  *
  * ==Registering and proving are two different things==
  *
  * Registering a key associates it with the account; it does not prove the account holder controls it. Forgejo marks an
  * unproven key `verified: false`, and commits signed with it are not attributed. There are two ways to prove
  * possession, and this type covers the shorter one:
  *
  *   - fetch a [[GpgKeyToken]], sign it, and pass the signature to [[provingPossession]] here, so registration and
  *     proof are one call;
  *   - register without a signature and prove later through [[GpgKeyToken.signedWith]] and `verifyGpgKey`.
  *
  * The signature in both cases is over the '''verification token''', not over the key and not over anything the caller
  * chooses. A signature over anything else is rejected by the instance with `422`. That ordering is enforced by the
  * types on the second route and only documented on this one, because the spec permits the field to be sent alone.
  *
  * @param armoredPublicKey
  *   the armored public key block, as `gpg --armor --export` prints it
  * @param armoredSignature
  *   a detached signature over a [[GpgKeyToken]], when the caller is proving possession in the same call
  */
final case class CreateGpgKey private (armoredPublicKey: String, armoredSignature: Option[ArmoredSignature]):

  /** The same request, additionally claiming possession with a signature over a [[GpgKeyToken]].
    *
    * @param signature
    *   the armored detached signature over the token's text — see the class note on what must have been signed
    */
  def provingPossession(signature: ArmoredSignature): CreateGpgKey =
    copy(armoredSignature = Some(signature))

object CreateGpgKey:

  /** The field name a rejected key block is reported under. */
  private val Field: String = "armoredPublicKey"

  /** Describes a key to register.
    *
    * '''Not trimmed.''' An armored block's line structure is part of what OpenPGP parses, so the value travels exactly
    * as given; only an empty one is rejected. Whether the block parses as a public key is the instance's judgement, and
    * it reports a bad one as `422`.
    *
    * @param armoredPublicKey
    *   the armored public key block
    * @return
    *   a registration request carrying no proof of possession, or a [[ValidationError]] on the `"armoredPublicKey"`
    *   field
    */
  def of(armoredPublicKey: String): Either[ValidationError, CreateGpgKey] =
    if armoredPublicKey.isEmpty then Left(ValidationError(Field, "must not be empty"))
    else Right(CreateGpgKey(armoredPublicKey, None))
