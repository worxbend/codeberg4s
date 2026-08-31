package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.{GitReference, RefName}

/** Forgejo's `Reference` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The ref listing has no golden fixture, so these three
  * keys are the spec's and nothing here claims a live instance was measured.
  *
  * @param ref
  *   the `ref` key: the fully qualified ref name, such as `refs/heads/main`
  * @param url
  *   the `url` key
  * @param obj
  *   the `object` key, renamed because `object` is a Scala keyword
  */
final case class ReferenceDto(ref: Option[String], url: Option[String], obj: Option[GitObjectDto]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `ref` and puts it through [[com.worxbend.codeberg4s.repositories.gitdata.RefName.from]]: an unnamed ref
    * cannot be addressed, and a name carrying a `..` segment must not reach a request path. A failure inside `object`
    * is reported at `object`'s own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitReference] =
    for
      name   <- Wire.validated(at, "ref", ref)(RefName.from)
      target <- Wire.nested(at, "object", obj)(_.toDomainAt(_))
    yield GitReference(name = name, url = url, target = target)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, GitReference] =
    toDomainAt(JsonPath.Root)

object ReferenceDto:

  /** Reads a `Reference` object. */
  given JsonDecoder[ReferenceDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ReferenceDto =
    ReferenceDto(
      ref = fields.text("ref"),
      url = fields.text("url"),
      obj = fields.nested("object").map(GitObjectDto.fromFields),
    )
