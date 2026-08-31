package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.hooks.{IssueFormField, IssueFormFieldType, IssueTemplate}

/** Forgejo's `IssueFormField` model — one control of an issue form template.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  *
  * `attributes` and `validations` are declared `additionalProperties: {}` — objects whose values may be anything — so
  * they are read by [[HookWire.textMap]] under the rule [[com.worxbend.codeberg4s.repositories.hooks.IssueFormField]]
  * states: a JSON string is unwrapped, anything else keeps its compact JSON rendering. That is the only reading
  * available to a `domain` module that depends on nothing but the standard library and therefore cannot hold a JSON
  * tree.
  */
final case class IssueFormFieldDto(
    id: Option[String],
    fieldType: Option[String],
    attributes: Map[String, String],
    validations: Map[String, String],
    visible: Vector[String],
):

  /** Converts to the domain. '''Cannot fail''': a form field has no property the domain insists on, and
    * [[com.worxbend.codeberg4s.repositories.hooks.IssueFormFieldType.parse]] is total.
    */
  def toDomain: IssueFormField =
    IssueFormField(
      id          = id,
      fieldType   = fieldType.map(IssueFormFieldType.parse),
      attributes  = attributes,
      validations = validations,
      visible     = visible,
    )

object IssueFormFieldDto:

  /** Reads an `IssueFormField` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[IssueFormFieldDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the template that embeds these. */
  def fromFields(fields: JsonFields): IssueFormFieldDto =
    IssueFormFieldDto(
      id          = fields.text("id"),
      fieldType   = fields.text("type"),
      attributes  = HookWire.textMap(fields, "attributes"),
      validations = HookWire.textMap(fields, "validations"),
      visible     = fields.texts("visible"),
    )

/** Forgejo's `IssueTemplate` model — one template a repository offers.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  *
  * Note the two spellings the spec uses for one idea: the template's fields arrive under the key `body`, while the
  * Markdown body of a non-form template arrives under `content`. Rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] keeps both spellings in exactly one place, this reader, and the
  * domain names them [[com.worxbend.codeberg4s.repositories.hooks.IssueTemplate.fields]] and
  * [[com.worxbend.codeberg4s.repositories.hooks.IssueTemplate.content]].
  */
final case class IssueTemplateDto(
    fileName: Option[String],
    name: Option[String],
    about: Option[String],
    title: Option[String],
    content: Option[String],
    labels: Vector[String],
    ref: Option[String],
    fields: Vector[IssueFormFieldDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `file_name` is required, because it is what identifies the template: two templates can share a display name, and
    * the file name is what a caller quotes when reporting which one is wrong. Everything else is absence-tolerant, and
    * the fields cannot fail — see [[IssueFormFieldDto.toDomain]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, IssueTemplate] =
    Wire
      .required(at, "file_name", fileName)
      .map: file =>
        IssueTemplate(
          fileName = file,
          name     = name,
          about    = about,
          title    = title,
          content  = content,
          labels   = labels,
          ref      = ref,
          fields   = fields.map(_.toDomain),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, IssueTemplate] =
    toDomainAt(JsonPath.Root)

object IssueTemplateDto:

  /** The wire key the form fields sit under — `body`, not `fields`. See the class note. */
  val FieldsKey: String = "body"

  /** Reads an `IssueTemplate` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[IssueTemplateDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): IssueTemplateDto =
    IssueTemplateDto(
      fileName = fields.text("file_name"),
      name     = fields.text("name"),
      about    = fields.text("about"),
      title    = fields.text("title"),
      content  = fields.rawText("content"),
      labels   = fields.texts("labels"),
      ref      = fields.text("ref"),
      fields   = fields.nestedAll(FieldsKey).map(IssueFormFieldDto.fromFields),
    )

  /** Converts a decoded array of templates, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[IssueTemplateDto]): Either[DecodeFailure, Vector[IssueTemplate]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
