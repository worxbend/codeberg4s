package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.social.HeatmapEntry

import java.time.Instant

/** Forgejo's `UserHeatmapData` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': `/users/{username}/heatmap` was not among
  * the 61 paths in the anonymous golden harvest. Both declared properties are represented and both are `Option`, per
  * rule 2 of [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * ==`timestamp` is a number, not a string==
  *
  * Every other timestamp this library reads is an RFC-3339 string handled by
  * [[com.worxbend.codeberg4s.codec.Timestamps]]. This one is not: the spec types it as `TimeStamp`, which it defines as
  * a bare `int64`, and that is seconds since the Unix epoch. It is therefore read with
  * [[com.worxbend.codeberg4s.codec.JsonFields.number]] and converted with [[java.time.Instant.ofEpochSecond]], and
  * neither the zero-time sentinel nor the epoch sentinel `Timestamps` folds away applies here — there is no measured
  * evidence that Forgejo uses `0` to mean "never" on this endpoint, so a `0` is carried as the epoch rather than
  * silently dropped.
  *
  * @param timestamp
  *   the `timestamp` key, in epoch seconds
  * @param contributions
  *   the `contributions` key
  */
final case class HeatmapEntryDto(timestamp: Option[Long], contributions: Option[Long]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Both fields are required, because the object has only these two. A bucket without a `timestamp` cannot be placed
    * on a timeline, and a bucket without a `contributions` count would have to be shown as zero — which is a value a
    * caller would plot, and therefore a lie rather than a gap. Reporting the absence at `$.timestamp` or
    * `$.contributions` is the only honest reading.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, HeatmapEntry] =
    for
      moment <- Wire.required(at, "timestamp", timestamp)
      count  <- Wire.required(at, "contributions", contributions)
    yield HeatmapEntry(at = Instant.ofEpochSecond(moment), contributions = count)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, HeatmapEntry] =
    toDomainAt(JsonPath.Root)

object HeatmapEntryDto:

  /** Reads a `UserHeatmapData` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[HeatmapEntryDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): HeatmapEntryDto =
    HeatmapEntryDto(
      timestamp     = fields.number("timestamp"),
      contributions = fields.number("contributions"),
    )

  /** Converts a decoded array of buckets, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[HeatmapEntryDto]): Either[DecodeFailure, Vector[HeatmapEntry]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
