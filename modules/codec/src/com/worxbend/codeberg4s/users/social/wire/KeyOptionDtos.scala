package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.users.social.{CreateGpgKey, CreateSshKey, VerifyGpgKey}

/** Forgejo's `CreateKeyOption` request model — the body of `POST /user/keys`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds.
  *
  * All three keys are always emitted. `title` and `key` are the model's required properties, and `read_only` is emitted
  * even though it is optional because [[com.worxbend.codeberg4s.users.social.CreateSshKey]] carries a plain `Boolean`
  * rather than an option — see that type for why an omitted `read_only` and an explicit `false` are the same request to
  * the instance.
  *
  * '''Derived from `spec/swagger.v1.json`''': no golden capture exists, since the endpoint needs a token.
  */
private[codeberg4s] object CreateKeyOptionDto:

  /** The wire key the label is sent under. */
  val TitleKey: String = "title"

  /** The wire key the material is sent under. */
  val KeyKey: String = "key"

  /** The wire key the fetch-only flag is sent under. */
  val ReadOnlyKey: String = "read_only"

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateSshKey): String =
    Json.render(
      JsonValue.Obj(
        TitleKey    -> JsonValue.Str(command.title),
        KeyKey      -> JsonValue.Str(command.key),
        ReadOnlyKey -> JsonValue.Bool(command.isReadOnly),
      )
    )

/** Forgejo's `CreateGPGKeyOption` request model — the body of `POST /user/gpg_keys`.
  *
  * `armored_public_key` is the model's single required property and is always emitted. `armored_signature` is emitted
  * only when the caller supplied one, because sending `""` would ask the instance to verify an empty signature and earn
  * a `422` where omitting the key asks it to register the key unverified — two different requests that must not
  * collapse into one.
  *
  * '''Derived from `spec/swagger.v1.json`''': no golden capture exists, since the endpoint needs a token.
  */
private[codeberg4s] object CreateGpgKeyOptionDto:

  /** The wire key the armored public key is sent under. */
  val ArmoredPublicKeyKey: String = "armored_public_key"

  /** The wire key the proof of possession is sent under. Shared with [[VerifyGpgKeyOptionDto]]. */
  val ArmoredSignatureKey: String = "armored_signature"

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateGpgKey): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: CreateGpgKey): List[(String, JsonValue)] =
    List(
      Some(ArmoredPublicKeyKey -> JsonValue.Str(command.armoredPublicKey)),
      command.armoredSignature.map(signature => ArmoredSignatureKey -> JsonValue.Str(signature.value)),
    ).flatten

/** Forgejo's `VerifyGPGKeyOption` request model — the body of `POST /user/gpg_key_verify`.
  *
  * Both keys are always emitted. `key_id` is the model's declared required property, and the signature is required by
  * [[com.worxbend.codeberg4s.users.social.VerifyGpgKey]] itself: a verification request without one asks the instance
  * to verify nothing.
  *
  * The signature travels through `Json.render`, which escapes it into a JSON string — so an armored block's newlines
  * survive intact and cannot break out of the body. That matters more here than it looks: an armored signature is
  * multi-line by construction, and hand-assembling this body is exactly how a client corrupts one.
  *
  * '''Derived from `spec/swagger.v1.json`''': no golden capture exists, since the endpoint needs a token.
  */
private[codeberg4s] object VerifyGpgKeyOptionDto:

  /** The wire key the OpenPGP identifier is sent under. */
  val KeyIdKey: String = "key_id"

  /** The wire key the signature is sent under. Shared with [[CreateGpgKeyOptionDto]]. */
  val ArmoredSignatureKey: String = "armored_signature"

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: VerifyGpgKey): String =
    Json.render(
      JsonValue.Obj(
        KeyIdKey            -> JsonValue.Str(command.keyId.value),
        ArmoredSignatureKey -> JsonValue.Str(command.armoredSignature.value),
      )
    )
