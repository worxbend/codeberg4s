package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.ActionVariable
import com.worxbend.codeberg4s.repositories.actions.VariableName
import com.worxbend.codeberg4s.repositories.wire.Elements

/** Forgejo's `ActionVariable` model, field for field.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * The value arrives under the key `data`, not `value` — which is the '''opposite''' of the request models, where
  * creating and updating both send `value`. Two spellings for one concept in one endpoint pair is exactly the kind of
  * detail rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] wants written down once, here, rather than
  * remembered at four call sites.
  */
final case class ActionVariableDto(
    name: Option[String],
    data: Option[String],
    ownerId: Option[Long],
    repoId: Option[Long],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Both `name` and `data` are required: the name addresses the variable, and the value is the only thing a caller
    * asked for. The value is read with [[com.worxbend.codeberg4s.codec.JsonFields.rawText]] rather than `text`, so an
    * empty string stays an empty string — a variable deliberately set to `""` is not an absent variable, and folding
    * the two together would make `Wire.required` reject a perfectly good one.
    *
    * `owner_id` and `repo_id` are dropped when they are `0`, per
    * [[com.worxbend.codeberg4s.repositories.actions.wire.ActionWire.identifier]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionVariable] =
    for
      variable <- Wire.validated(at, "name", name)(VariableName.from)
      content  <- Wire.required(at, "data", data)
    yield ActionVariable(
      name    = variable,
      value   = content,
      ownerId = ActionWire.identifier(ownerId),
      repoId  = ActionWire.identifier(repoId),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ActionVariable] =
    toDomainAt(JsonPath.Root)

object ActionVariableDto:

  /** Reads an `ActionVariable` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionVariableDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionVariableDto =
    ActionVariableDto(
      name    = fields.text("name"),
      data    = fields.rawText("data"),
      ownerId = fields.number("owner_id"),
      repoId  = fields.number("repo_id"),
    )

  /** Converts a decoded array of variables, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[ActionVariableDto]): Either[DecodeFailure, Vector[ActionVariable]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
