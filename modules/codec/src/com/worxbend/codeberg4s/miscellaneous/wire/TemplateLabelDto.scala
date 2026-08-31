package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.LabelColor
import com.worxbend.codeberg4s.miscellaneous.TemplateLabel

/** Forgejo's `LabelTemplate` model — one element of `GET /label/templates/{name}`.
  *
  * The domain type is called [[com.worxbend.codeberg4s.miscellaneous.TemplateLabel]]; see it for why the wire name is
  * not repeated there.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' No golden fixture exists for either
  * label-template endpoint.
  *
  * @param color
  *   `color`, a hexadecimal triplet the spec exemplifies as `00aabb`
  * @param description
  *   `description`, free text
  * @param exclusive
  *   `exclusive`, whether the seeded label is exclusive within its `scope/` prefix
  * @param name
  *   `name`, the label's text
  */
final case class TemplateLabelDto(
    color: Option[String],
    description: Option[String],
    exclusive: Option[Boolean],
    name: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `name` is the only required field. The colour is '''not''' required and not a failure when it will not parse: a
    * template entry whose colour Forgejo spelled in a way [[com.worxbend.codeberg4s.issues.LabelColor]] does not
    * recognise still names a label, and dropping the whole template over one bad triplet would cost the caller data the
    * instance actually holds. That is the same leniency [[com.worxbend.codeberg4s.issues.wire.LabelDto]] applies to a
    * repository label.
    *
    * `exclusive` defaults to `false` when absent, which is the conservative reading: an instance that does not say a
    * label is exclusive is not promising that it is.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, TemplateLabel] =
    Wire
      .required(at, "name", name)
      .map(text =>
        TemplateLabel(
          name        = text,
          color       = color.flatMap(LabelColor.from(_).toOption),
          description = description,
          isExclusive = exclusive.getOrElse(false),
        )
      )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, TemplateLabel] =
    toDomainAt(JsonPath.Root)

object TemplateLabelDto:

  /** Reads a `LabelTemplate` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[TemplateLabelDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): TemplateLabelDto =
    TemplateLabelDto(
      color       = fields.text("color"),
      description = fields.text("description"),
      exclusive   = fields.boolean("exclusive"),
      name        = fields.text("name"),
    )

  /** Converts a decoded array of template labels, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[TemplateLabelDto]): Either[DecodeFailure, Vector[TemplateLabel]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
