package com.worxbend.codeberg4s.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.ServerVersion
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure

/** The body of `GET /version`, verbatim.
  *
  * The whole payload is one key — `golden/version/version.json` is
  * `{"version": "16.0.0-dev-668-1bdb1938+gitea-1.22.0"}` — which makes this the smallest possible worked example of
  * [[com.worxbend.codeberg4s.codec.WireConventions]]: the field is `Option` even though it is the only field the
  * endpoint has, because the spec declares nothing `required` and a decoder that assumes otherwise is asserting
  * something nobody verified.
  *
  * @param version
  *   the `version` key, blank folded to `None`
  */
final case class ServerVersionDto(version: Option[String]):

  /** Converts to the domain, reporting the path of the offending field relative to `at`.
    *
    * Fails with [[com.worxbend.codeberg4s.core.DecodeFailure]] when `version` is absent, `null`, blank, or not a
    * string: an instance that cannot name itself has not answered the question that was asked.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ServerVersion] =
    Wire.required(at, "version", version).map(ServerVersion.apply)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ServerVersion] =
    toDomainAt(JsonPath.Root)

object ServerVersionDto:

  /** Reads a `/version` body. Absent, `null` and non-string all decode to `None`; the document must still be a JSON
    * object, which upickle enforces.
    */
  given upickle.default.Reader[ServerVersionDto] =
    JsonFields.reader(fields => ServerVersionDto(version = fields.text("version")))
