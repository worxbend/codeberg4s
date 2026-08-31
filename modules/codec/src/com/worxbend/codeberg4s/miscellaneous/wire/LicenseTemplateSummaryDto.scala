package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.{LicenseTemplateSummary, TemplateName}

/** Forgejo's `LicensesTemplateListEntry` model — one element of `GET /licenses`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response'''; `golden/MANIFEST.md` records that
  * `GET /licenses` was probed and dropped as fixture noise, so no capture exists.
  *
  * @param key
  *   `key`, the instance's first spelling of the identifier
  * @param name
  *   `name`, what the by-name endpoint is given
  * @param url
  *   `url`, the API URL of the template
  */
final case class LicenseTemplateSummaryDto(
    key: Option[String],
    name: Option[String],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `name` is required '''and''' validated: an entry whose name cannot address `/licenses/{name}` is an entry the
    * caller could do nothing with, so it is reported at `$[n].name` rather than handed over. [[TemplateName]] accepts
    * spaces, which license names genuinely carry.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, LicenseTemplateSummary] =
    Wire
      .validated(at, "name", name)(TemplateName.from)
      .map(templateName => LicenseTemplateSummary(name = templateName, key = key, url = url))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, LicenseTemplateSummary] =
    toDomainAt(JsonPath.Root)

object LicenseTemplateSummaryDto:

  /** Reads a `LicensesTemplateListEntry` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[LicenseTemplateSummaryDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): LicenseTemplateSummaryDto =
    LicenseTemplateSummaryDto(
      key  = fields.text("key"),
      name = fields.text("name"),
      url  = fields.text("url"),
    )

  /** Converts a decoded array of entries, reporting the position of whichever element failed. */
  def toDomainAll(
      base: JsonPath,
      dtos: Vector[LicenseTemplateSummaryDto],
  ): Either[DecodeFailure, Vector[LicenseTemplateSummary]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
