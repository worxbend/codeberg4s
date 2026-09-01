package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.LicenseTemplate

/** Forgejo's `LicenseTemplateInfo` model — the body of `GET /licenses/{name}`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response'''; see [[LicenseTemplateSummaryDto]].
  *
  * @param body
  *   `body`, the license text
  * @param implementation
  *   `implementation`, Forgejo's prose note on applying the license
  * @param key
  *   `key`, one spelling of the identifier
  * @param name
  *   `name`, the other
  * @param url
  *   `url`, the API URL of the template
  */
final case class LicenseTemplateDto(
    body: Option[String],
    implementation: Option[String],
    key: Option[String],
    name: Option[String],
    url: Option[String],
) extends WireModel[LicenseTemplate]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `body` is the only required field, because it is the only one the call was made for: the caller supplied the name
    * and gets the text back. A response carrying no text is reported at `$.body`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, LicenseTemplate] =
    Wire
      .required(at, "body", body)
      .map(text =>
        LicenseTemplate(
          body           = text,
          name           = name,
          key            = key,
          implementation = implementation,
          url            = url,
        )
      )

object LicenseTemplateDto:

  /** Reads a `LicenseTemplateInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[LicenseTemplateDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object.
    *
    * `body` is read with `rawText`, so a license file that is genuinely empty decodes rather than failing; every other
    * field uses `text`, which folds Forgejo's `""`-for-absent convention away.
    */
  def fromFields(fields: JsonFields): LicenseTemplateDto =
    LicenseTemplateDto(
      body           = fields.rawText("body"),
      implementation = fields.text("implementation"),
      key            = fields.text("key"),
      name           = fields.text("name"),
      url            = fields.text("url"),
    )
