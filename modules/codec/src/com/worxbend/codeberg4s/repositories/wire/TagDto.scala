package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.CommitRef
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.Tag
import com.worxbend.codeberg4s.repositories.TagName

/** Forgejo's `Tag` model, field for field.
  *
  * Every key on the three elements of `golden/repository/tags-list.json` is represented.
  *
  * @param name
  *   the `name` key
  * @param message
  *   the `message` key: the annotation. Absent for a lightweight tag, and Forgejo spells that absence `""`
  * @param id
  *   the `id` key: the object the tag points at, spelled as an id rather than a sha at this one place in the API
  * @param commit
  *   the `commit` key
  * @param zipballUrl
  *   the `zipball_url` key
  * @param tarballUrl
  *   the `tarball_url` key
  * @param archiveDownloadCount
  *   the `archive_download_count` key
  */
final case class TagDto(
    name: Option[String],
    message: Option[String],
    id: Option[String],
    commit: Option[CommitMetaDto],
    zipballUrl: Option[String],
    tarballUrl: Option[String],
    archiveDownloadCount: Option[ArchiveDownloadCountDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `name` and `id`, both through their smart constructors: a tag with no name cannot be addressed and a tag
    * with no id points nowhere. A failure inside `commit` is reported at `commit`'s own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Tag] =
    for
      tagName   <- Wire.validated(at, "name", name)(TagName.from)
      target    <- Wire.validated(at, "id", id)(CommitSha.from)
      reference <- commitAt(at)
    yield Tag(
      name             = tagName,
      message          = message,
      commitSha        = target,
      commit           = reference,
      zipballUrl       = zipballUrl,
      tarballUrl       = tarballUrl,
      archiveDownloads = archiveDownloadCount.map(_.toDomain),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Tag] =
    toDomainAt(JsonPath.Root)

  private def commitAt(at: JsonPath): Either[DecodeFailure, Option[CommitRef]] =
    commit.fold(Right(None))(dto => dto.toDomainAt(at.field("commit")).map(Some.apply))

object TagDto:

  /** Reads a `Tag` object. */
  given upickle.default.Reader[TagDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): TagDto =
    TagDto(
      name                 = fields.text("name"),
      message              = fields.text("message"),
      id                   = fields.text("id"),
      commit               = fields.nested("commit").map(CommitMetaDto.fromFields),
      zipballUrl           = fields.text("zipball_url"),
      tarballUrl           = fields.text("tarball_url"),
      archiveDownloadCount = fields.nested("archive_download_count").map(ArchiveDownloadCountDto.fromFields),
    )
