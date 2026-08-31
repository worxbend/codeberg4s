package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.gitdata.FileCommit
import com.worxbend.codeberg4s.repositories.wire.{CommitMetaDto, GitIdentityDto}

/** Forgejo's `FileCommitResponse` — the commit an editing endpoint reports it wrote.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `author` and `committer` are `CommitUser` objects and
  * therefore [[com.worxbend.codeberg4s.repositories.wire.GitIdentityDto]]s; `tree` and every element of `parents` are
  * `CommitMeta` objects and therefore [[com.worxbend.codeberg4s.repositories.wire.CommitMetaDto]]s.
  *
  * @param sha
  *   the `sha` key
  * @param message
  *   the `message` key
  * @param author
  *   the `author` key
  * @param committer
  *   the `committer` key
  * @param tree
  *   the `tree` key
  * @param parents
  *   the `parents` key
  * @param created
  *   the `created` key as a raw string
  * @param url
  *   the `url` key
  * @param htmlUrl
  *   the `html_url` key
  */
final case class FileCommitDto(
    sha: Option[String],
    message: Option[String],
    author: Option[GitIdentityDto],
    committer: Option[GitIdentityDto],
    tree: Option[CommitMetaDto],
    parents: Vector[CommitMetaDto],
    created: Option[String],
    url: Option[String],
    htmlUrl: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `sha`, through [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: the whole point of the response
    * is to name the commit that was written. A failure inside `tree` or `parents` is reported at its own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, FileCommit] =
    for
      identifier <- Wire.validated(at, "sha", sha)(CommitSha.from)
      root       <- Wire.nested(at, "tree", tree)(_.toDomainAt(_))
      ancestors  <- ArrayElements.convert(at.field("parents"), parents)((dto, path) => dto.toDomainAt(path))
    yield FileCommit(
      sha       = identifier,
      message   = message,
      author    = author.map(_.toDomain),
      committer = committer.map(_.toDomain),
      tree      = root,
      parents   = ancestors,
      created   = Timestamps.parseOptional(created),
      url       = url,
      htmlUrl   = htmlUrl,
    )

object FileCommitDto:

  /** Reads a `FileCommitResponse` object. */
  given JsonDecoder[FileCommitDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the file-response DTO that embeds this one. */
  def fromFields(fields: JsonFields): FileCommitDto =
    FileCommitDto(
      sha       = fields.text("sha"),
      message   = fields.text("message"),
      author    = fields.nested("author").map(GitIdentityDto.fromFields),
      committer = fields.nested("committer").map(GitIdentityDto.fromFields),
      tree      = fields.nested("tree").map(CommitMetaDto.fromFields),
      parents   = fields.nestedAll("parents").map(CommitMetaDto.fromFields),
      created   = fields.text("created"),
      url       = fields.text("url"),
      htmlUrl   = fields.text("html_url"),
    )
