package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.{ActionArtifact, ArtifactId, RunId}

/** Forgejo's `ActionArtifact` model, field for field.
  *
  * ==Derived from the spec, not from a capture==
  *
  * There is no golden fixture for any Actions endpoint: the harvest behind `modules/codec/test/resources/golden` was
  * anonymous and every endpoint in this group needs a token. The field set below is therefore `spec/swagger.v1.json`'s
  * `ActionArtifact` definition read literally, and the tests that exercise it decode hand-written payloads shaped to
  * that definition. That is weaker evidence than the rest of this module has, and it is said out loud rather than
  * implied.
  *
  * The nullability treatment is unchanged: every field is optional, and absent, `null` and wrong-typed are one and the
  * same to [[com.worxbend.codeberg4s.codec.JsonFields]] — `docs/HAZARDS.md` §1 measured that no response definition in
  * the pinned spec declares `required`, so a spec-derived DTO gets exactly the same treatment as a measured one.
  */
final case class ActionArtifactDto(
    id: Option[Long],
    name: Option[String],
    sizeInBytes: Option[Long],
    expired: Option[Boolean],
    archiveDownloadUrl: Option[String],
    runId: Option[Long],
    createdAt: Option[String],
    updatedAt: Option[String],
    expiresAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required, because it is the only thing without which the artifact cannot be addressed, downloaded or
    * deleted. `expired` absent becomes `false` — the artifact is listed, so its bytes are presumed present — and every
    * timestamp goes through [[com.worxbend.codeberg4s.codec.Timestamps]], where Forgejo's zero-time sentinel is folded
    * into absence.
    *
    * A `run_id` that is not a positive identifier is dropped rather than failing: an artifact whose producing run
    * cannot be named is still a perfectly good artifact.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionArtifact] =
    Wire
      .validated(at, "id", id)(ArtifactId.from)
      .map: identifier =>
        ActionArtifact(
          id                 = identifier,
          name               = name,
          sizeInBytes        = sizeInBytes,
          isExpired          = expired.getOrElse(false),
          archiveDownloadUrl = archiveDownloadUrl,
          runId              = runId.flatMap(value => RunId.from(value).toOption),
          createdAt          = Timestamps.parseOptional(createdAt),
          updatedAt          = Timestamps.parseOptional(updatedAt),
          expiresAt          = Timestamps.parseOptional(expiresAt),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ActionArtifact] =
    toDomainAt(JsonPath.Root)

object ActionArtifactDto:

  /** Reads an `ActionArtifact` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionArtifactDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionArtifactDto =
    ActionArtifactDto(
      id                 = fields.number("id"),
      name               = fields.text("name"),
      sizeInBytes        = fields.number("size_in_bytes"),
      expired            = fields.boolean("expired"),
      archiveDownloadUrl = fields.text("archive_download_url"),
      runId              = fields.number("run_id"),
      createdAt          = fields.text("created_at"),
      updatedAt          = fields.text("updated_at"),
      expiresAt          = fields.text("expires_at"),
    )

  /** Converts a decoded array of artifacts, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[ActionArtifactDto]): Either[DecodeFailure, Vector[ActionArtifact]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
