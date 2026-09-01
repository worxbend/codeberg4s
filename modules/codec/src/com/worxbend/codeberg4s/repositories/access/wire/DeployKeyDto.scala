package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.access.{DeployKey, DeployKeyId}
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto

/** The wire spelling of every property a deploy key has, written down exactly once.
  *
  * Three of them — [[Key]], [[Title]] and [[ReadOnly]] — are also properties of `CreateKeyOption`, which is why they
  * live here rather than in either model; see [[BranchProtectionWire]] for the argument.
  */
private[codeberg4s] object DeployKeyWire:

  /** The grant's own identifier, which `DELETE /repos/{owner}/{repo}/keys/{id}` takes. */
  val Id: String = "id"

  /** The identifier of the underlying SSH key row, which the listing's own `key_id` filter matches. Not [[Id]]. */
  val KeyId: String = "key_id"

  /** The armoured public key. Shared with `CreateKeyOption`. */
  val Key: String = "key"

  /** The label the key is listed under. Shared with `CreateKeyOption`. */
  val Title: String = "title"

  /** Whether the key may fetch but not push. Shared with `CreateKeyOption`. */
  val ReadOnly: String = "read_only"

  /** The instance's fingerprint of the key. Response-only. */
  val Fingerprint: String = "fingerprint"

  /** The API URL of the key. Response-only. */
  val Url: String = "url"

  /** The repository the key grants access to, embedded whole. Response-only. */
  val Repository: String = "repository"

  /** When the key was registered. Response-only. */
  val CreatedAt: String = "created_at"

/** Forgejo's `DeployKey` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[BranchProtectionDto]] for the evidence note
  * that applies to every model in this group.
  *
  * `repository` nests [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] rather than flattening a slug out of
  * it, per rule 6 of [[com.worxbend.codeberg4s.codec.WireConventions]]. That model is owned by the repository group and
  * reused here, not forked.
  *
  * '''Nothing in this DTO is redacted''', and the key material least of all: it is the public half. See
  * [[com.worxbend.codeberg4s.repositories.access.DeployKey]] for the full argument, which is worth reading before
  * anyone "fixes" this by wrapping `key` in a masking type.
  */
final case class DeployKeyDto(
    id: Option[Long],
    keyId: Option[Long],
    key: Option[String],
    title: Option[String],
    fingerprint: Option[String],
    url: Option[String],
    repository: Option[RepositoryDto],
    readOnly: Option[Boolean],
    createdAt: Option[String],
) extends WireModel[DeployKey]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two fields are required. `id` goes through [[com.worxbend.codeberg4s.repositories.access.DeployKeyId.from]]
    * because it is the only thing that addresses the grant, and `key` is the material — an entry without it authorises
    * nothing and identifies nothing.
    *
    * `read_only` absent becomes `false`, which is read-write; see the domain model for why the permissive reading is
    * the conservative one for an auditor. A failure inside the embedded `repository` is reported at `$.repository.…`,
    * by that model.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, DeployKey] =
    for
      identifier <- Wire.validated(at, DeployKeyWire.Id, id)(DeployKeyId.from)
      material   <- Wire.required(at, DeployKeyWire.Key, key)
      repo       <- Wire.nested(at, DeployKeyWire.Repository, repository)(_.toDomainAt(_))
    yield DeployKey(
      id          = identifier,
      key         = material,
      keyId       = keyId,
      title       = title,
      fingerprint = fingerprint,
      url         = url,
      repository  = repo,
      isReadOnly  = readOnly.getOrElse(false),
      createdAt   = Timestamps.parseOptional(createdAt),
    )

object DeployKeyDto:

  /** Reads a `DeployKey` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[DeployKeyDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto.fromFields]]
    * for the embedded repository so that no field spelling is written twice.
    */
  def fromFields(fields: JsonFields): DeployKeyDto =
    DeployKeyDto(
      id          = fields.number(DeployKeyWire.Id),
      keyId       = fields.number(DeployKeyWire.KeyId),
      key         = fields.text(DeployKeyWire.Key),
      title       = fields.text(DeployKeyWire.Title),
      fingerprint = fields.text(DeployKeyWire.Fingerprint),
      url         = fields.text(DeployKeyWire.Url),
      repository  = fields.nested(DeployKeyWire.Repository).map(RepositoryDto.fromFields),
      readOnly    = fields.boolean(DeployKeyWire.ReadOnly),
      createdAt   = fields.text(DeployKeyWire.CreatedAt),
    )

  /** Converts a decoded array of deploy keys, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[DeployKeyDto]): Either[DecodeFailure, Vector[DeployKey]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
