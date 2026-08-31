package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.GitBlob
import com.worxbend.codeberg4s.repositories.{CommitSha, FileContent}

/** Forgejo's `GitBlob` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' No golden fixture exists for the blob endpoints, so
  * the five keys below are the five the pinned specification declares and nothing here asserts what a live instance
  * actually sends. Every field is optional, as rule 2 of [[com.worxbend.codeberg4s.codec.WireConventions]] requires and
  * as `docs/HAZARDS.md` §1 justifies.
  *
  * @param content
  *   the `content` key: the blob's bytes, encoded
  * @param encoding
  *   the `encoding` key, which Forgejo populates alongside `content`
  * @param sha
  *   the `sha` key: the blob's object id
  * @param size
  *   the `size` key, in bytes
  * @param url
  *   the `url` key
  */
final case class GitBlobDto(
    content: Option[String],
    encoding: Option[String],
    sha: Option[String],
    size: Option[Long],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `sha`, through [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: it is the blob's identity, the
    * only thing a follow-up call can be made with, and a non-hexadecimal value would forge a path if it were let
    * through. Everything else is optional — a blob the instance declined to inline, because it exceeds
    * `default_max_blob_size`, is a blob with a size and no content.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitBlob] =
    Wire
      .validated(at, "sha", sha)(CommitSha.from)
      .map(id => GitBlob(sha = id, size = size.getOrElse(0L), content = fileContent, url = url))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, GitBlob] =
    toDomainAt(JsonPath.Root)

  /** Pairs `content` with `encoding`, exactly as the contents endpoint's DTO does, since the wire pair is the same.
    *
    * An encoding this library does not implement keeps the payload verbatim rather than dropping it — see
    * [[com.worxbend.codeberg4s.repositories.FileContent.Opaque]].
    */
  private def fileContent: Option[FileContent] =
    content.map: payload =>
      encoding.map(_.trim.toLowerCase) match
        case Some("base64") => FileContent.Base64(payload)
        case _              => FileContent.Opaque(encoding, payload)

object GitBlobDto:

  /** Reads a `GitBlob` object. */
  given JsonDecoder[GitBlobDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): GitBlobDto =
    GitBlobDto(
      content  = fields.rawText("content"),
      encoding = fields.text("encoding"),
      sha      = fields.text("sha"),
      size     = fields.number("size"),
      url      = fields.text("url"),
    )
