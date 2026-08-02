package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.repositories.admin.LanguageBreakdown

/** Forgejo's `LanguageStatistics` response — a bare object with no fixed keys.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The response is declared as
  * `{"type": "object", "additionalProperties": {"type": "integer", "format": "int64"}}`, which is to say: every key is
  * a language name Forgejo detected and every value is a byte count. There is no envelope, no `data` field and no
  * declared property, so this is the one shape in the library that cannot be a field-by-field DTO.
  *
  * ==Why the keys are not filtered==
  *
  * Every key is kept, whatever its value looked like on the wire: a value that is not a number is dropped rather than
  * failing the whole response, exactly as [[com.worxbend.codeberg4s.codec.JsonFields.number]] does for a named field. A
  * repository whose analysis has not run answers `{}`, which is a success carrying
  * [[com.worxbend.codeberg4s.repositories.admin.LanguageBreakdown.Empty]] and not a failure.
  *
  * @param counts
  *   the object's keys and their numeric values, in no particular order
  */
final case class LanguageStatisticsDto(counts: Map[String, Long]):

  /** Converts to the domain. Cannot fail — there is nothing the domain requires that this could be missing. */
  def toDomain: LanguageBreakdown =
    LanguageBreakdown(counts)

object LanguageStatisticsDto:

  /** Reads a bare `{"language": bytes}` object.
    *
    * Built on [[com.worxbend.codeberg4s.codec.JsonFields.reader]] like every other DTO here, so a body that is an
    * array, a number or malformed fails in the JSON parser with a recoverable path rather than raising from this
    * module.
    */
  given JsonDecoder[LanguageStatisticsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object by reading every key it happens to have. */
  def fromFields(fields: JsonFields): LanguageStatisticsDto =
    LanguageStatisticsDto(
      fields.underlying.keys.toVector.flatMap(name => fields.number(name).map(count => name -> count)).toMap
    )
