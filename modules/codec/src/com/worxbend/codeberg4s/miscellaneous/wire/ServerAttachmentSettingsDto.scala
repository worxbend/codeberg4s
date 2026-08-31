package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.ServerAttachmentSettings

/** Forgejo's `GeneralAttachmentSettings` model, field for field.
  *
  * All four keys of `golden/misc/settings-attachment.json` are represented, and every one is `Option` per rule 2 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * @param enabled
  *   `enabled`
  * @param allowedTypes
  *   `allowed_types`, one comma-separated string on the wire
  * @param maxSize
  *   `max_size`, in mebibytes
  * @param maxFiles
  *   `max_files`
  */
final case class ServerAttachmentSettingsDto(
    enabled: Option[Boolean],
    allowedTypes: Option[String],
    maxSize: Option[Long],
    maxFiles: Option[Long],
):

  /** Converts to the domain. Always a `Right`.
    *
    * An absent `enabled` becomes `false`. That is the conservative direction: a caller uses this flag to decide whether
    * to offer an upload at all, and offering one the instance will reject is worse than hiding one it would have
    * accepted.
    *
    * `allowed_types` is split here rather than in the domain, which is rule 5 of
    * [[com.worxbend.codeberg4s.codec.WireConventions]] — the comma-separated spelling is a wire convention and does not
    * reach a caller. Blank entries are dropped, so the trailing comma Forgejo's configuration file tolerates does not
    * become an empty content type.
    *
    * There is no `toDomainAt`: this model is never nested inside another response.
    */
  def toDomain: Either[DecodeFailure, ServerAttachmentSettings] =
    Right(
      ServerAttachmentSettings(
        enabled      = enabled.getOrElse(false),
        allowedTypes = ServerAttachmentSettingsDto.split(allowedTypes),
        maxSizeMib   = maxSize,
        maxFiles     = maxFiles,
      )
    )

object ServerAttachmentSettingsDto:

  /** The separator Forgejo puts between accepted content types. */
  private val TypeSeparator: Char = ','

  /** Reads a `/settings/attachment` body. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ServerAttachmentSettingsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ServerAttachmentSettingsDto =
    ServerAttachmentSettingsDto(
      enabled      = fields.boolean("enabled"),
      allowedTypes = fields.text("allowed_types"),
      maxSize      = fields.number("max_size"),
      maxFiles     = fields.number("max_files"),
    )

  private def split(raw: Option[String]): Vector[String] =
    raw.fold(Vector.empty)(_.split(TypeSeparator).toVector.map(_.trim).filter(_.nonEmpty))
