package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.access.TagProtection
import com.worxbend.codeberg4s.repositories.access.TagProtectionId
import com.worxbend.codeberg4s.repositories.wire.Elements

/** The wire spelling of every property a tag protection rule has, written down exactly once.
  *
  * Three of these names appear in the response model '''and''' in both request models, for the reason
  * [[BranchProtectionWire]] states at length: a request key that drifts from the reader's key is silently dropped by
  * Forgejo, and the caller is told `201` about a rule that does not do what they asked.
  */
private[codeberg4s] object TagProtectionWire:

  /** The rule's identifier. Response-only; neither request model declares it. */
  val Id: String = "id"

  /** The glob matched against tag names. */
  val NamePattern: String = "name_pattern"

  /** The accounts exempt from the rule. Plural here, unlike the branch model's approvals whitelist. */
  val WhitelistUsernames: String = "whitelist_usernames"

  /** The teams exempt from the rule. */
  val WhitelistTeams: String = "whitelist_teams"

  /** When the rule was created. Response-only. */
  val CreatedAt: String = "created_at"

  /** When the rule was last changed. Response-only. */
  val UpdatedAt: String = "updated_at"

/** Forgejo's `TagProtection` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[BranchProtectionDto]] for the evidence note
  * that applies to every model in this group.
  */
final case class TagProtectionDto(
    id: Option[Long],
    namePattern: Option[String],
    whitelistUsernames: Vector[String],
    whitelistTeams: Vector[String],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two fields are required. `id` goes through [[com.worxbend.codeberg4s.repositories.access.TagProtectionId.from]]
    * because it is the only way any endpoint addresses the rule, and `name_pattern` is the rule itself — a protection
    * that matches nothing protects nothing, and reporting one as though it were real would be worse than failing.
    *
    * The two whitelists are already empty vectors when the payload sent `null`, per
    * [[com.worxbend.codeberg4s.codec.JsonFields.texts]]; empty means nobody is exempt, which is this endpoint's
    * strictest state rather than its default one — see [[com.worxbend.codeberg4s.repositories.access.TagProtection]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, TagProtection] =
    for
      identifier <- Wire.validated(at, TagProtectionWire.Id, id)(TagProtectionId.from)
      pattern    <- Wire.required(at, TagProtectionWire.NamePattern, namePattern)
    yield TagProtection(
      id                 = identifier,
      namePattern        = pattern,
      whitelistUsernames = whitelistUsernames,
      whitelistTeams     = whitelistTeams,
      createdAt          = Timestamps.parseOptional(createdAt),
      updatedAt          = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, TagProtection] =
    toDomainAt(JsonPath.Root)

object TagProtectionDto:

  /** Reads a `TagProtection` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[TagProtectionDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): TagProtectionDto =
    TagProtectionDto(
      id                 = fields.number(TagProtectionWire.Id),
      namePattern        = fields.text(TagProtectionWire.NamePattern),
      whitelistUsernames = fields.texts(TagProtectionWire.WhitelistUsernames),
      whitelistTeams     = fields.texts(TagProtectionWire.WhitelistTeams),
      createdAt          = fields.text(TagProtectionWire.CreatedAt),
      updatedAt          = fields.text(TagProtectionWire.UpdatedAt),
    )

  /** Converts a decoded array of rules, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[TagProtectionDto]): Either[DecodeFailure, Vector[TagProtection]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
