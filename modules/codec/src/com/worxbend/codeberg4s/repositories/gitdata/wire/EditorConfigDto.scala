package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.gitdata.EditorConfigDefinitions

/** The body of `GET /repos/{owner}/{repo}/editorconfig/{filepath}` — a bare object of property names to values.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The spec declares the response as an object with
  * `additionalProperties: {type: string}` and no named property at all, so there is nothing here to enumerate and the
  * DTO is a map.
  *
  * ==Why the values are rendered rather than required to be strings==
  *
  * EditorConfig is a text format and the spec types every value as a string, but a JSON encoder that knows
  * `indent_size` is a number has every reason to send `4` rather than `"4"`. Refusing that would lose the property; so
  * would silently dropping it. A JSON number or boolean is therefore rendered back to the text it stands for, and only
  * a value that is a structure — an array, an object, or `null` — is dropped, because those have no text form an
  * EditorConfig consumer could use.
  *
  * A whole number renders without a fractional part: the document model parses every JSON number as a `Double`, so `4`
  * arrives as `4.0` and would read as `"4.0"` if it were rendered naively. That is the one piece of arithmetic in this
  * file and the reason it exists.
  *
  * @param values
  *   the properties, keyed as the instance named them
  */
final case class EditorConfigDto(values: Map[String, String]):

  /** Converts to the domain. Cannot fail: an empty object is a path with no EditorConfig properties, which is a normal
    * answer and not a defect.
    */
  def toDomain: EditorConfigDefinitions =
    EditorConfigDefinitions(values)

object EditorConfigDto:

  /** Reads the definitions object. */
  given JsonDecoder[EditorConfigDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, keeping only the properties that have a text form. */
  def fromFields(fields: JsonFields): EditorConfigDto =
    EditorConfigDto(fields.underlying.flatMap((name, value) => rendered(value).map(text => name -> text)))

  /** The text an EditorConfig consumer would have read, or `None` for a value that has none. */
  private def rendered(value: JsonValue): Option[String] =
    value.strOpt
      .orElse(value.numOpt.map(number))
      .orElse(value.boolOpt.map(_.toString))

  private def number(value: BigDecimal): String =
    if value.isWhole then value.toLong.toString else value.toString
