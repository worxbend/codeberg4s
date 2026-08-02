package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.GitignoreTemplate

/** Forgejo's `GitignoreTemplateInfo` model — the body of `GET /gitignore/templates/{name}`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' `golden/misc/gitignore-templates.json`
  * captures the '''listing''', which is a bare array of names and carries neither of these keys, so the two fields
  * below are the spec read literally.
  *
  * @param name
  *   `name`, the template's own name
  * @param source
  *   `source`, the file's contents
  */
final case class GitignoreTemplateDto(
    name: Option[String],
    source: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `source` is required and `name` is not, which is the split [[GitignoreTemplate]] explains: the caller asked for a
    * template by name, so the name is confirmation, and the contents are the answer.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitignoreTemplate] =
    Wire.required(at, "source", source).map(text => GitignoreTemplate(name = name, source = text))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, GitignoreTemplate] =
    toDomainAt(JsonPath.Root)

object GitignoreTemplateDto:

  /** Reads a `GitignoreTemplateInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[GitignoreTemplateDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object.
    *
    * `source` is read with `rawText` rather than `text`: a `.gitignore` template that is genuinely empty is a template,
    * not an absent field, and folding `""` into absence here would turn it into a decoding failure.
    */
  def fromFields(fields: JsonFields): GitignoreTemplateDto =
    GitignoreTemplateDto(
      name   = fields.text("name"),
      source = fields.rawText("source"),
    )
