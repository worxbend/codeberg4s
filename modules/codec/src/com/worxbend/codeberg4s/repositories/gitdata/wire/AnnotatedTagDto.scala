package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.AnnotatedTag
import com.worxbend.codeberg4s.repositories.wire.{ArchiveDownloadCountDto, GitIdentityDto, VerificationDto}
import com.worxbend.codeberg4s.repositories.{CommitSha, TagName}

/** Forgejo's `AnnotatedTag` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' No golden fixture covers `GET /git/tags/{sha}`, so
  * these eight keys are the spec's. Three of the nested objects are the repository wave's DTOs, reused rather than
  * re-declared: `tagger` is a `CommitUser` and therefore a
  * [[com.worxbend.codeberg4s.repositories.wire.GitIdentityDto]], `verification` is the same `PayloadCommitVerification`
  * a commit carries, and `archive_download_count` is the same object a tag carries.
  *
  * @param tag
  *   the `tag` key: the short tag name
  * @param sha
  *   the `sha` key: the id of the tag object itself, not of the commit
  * @param obj
  *   the `object` key, renamed because `object` is a Scala keyword
  * @param message
  *   the `message` key: the annotation
  * @param tagger
  *   the `tagger` key
  * @param verification
  *   the `verification` key
  * @param archiveDownloadCount
  *   the `archive_download_count` key
  * @param url
  *   the `url` key
  */
final case class AnnotatedTagDto(
    tag: Option[String],
    sha: Option[String],
    obj: Option[GitObjectDto],
    message: Option[String],
    tagger: Option[GitIdentityDto],
    verification: Option[VerificationDto],
    archiveDownloadCount: Option[ArchiveDownloadCountDto],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `tag` and `sha`, both through their smart constructors: the first is how the tag is addressed and the
    * second is the object it is. A failure inside `object` is reported at `object`'s own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, AnnotatedTag] =
    for
      name     <- Wire.validated(at, "tag", tag)(TagName.from)
      objectId <- Wire.validated(at, "sha", sha)(CommitSha.from)
      target   <- Wire.nested(at, "object", obj)(_.toDomainAt(_))
    yield AnnotatedTag(
      name             = name,
      sha              = objectId,
      target           = target,
      message          = message,
      tagger           = tagger.map(_.toDomain),
      verification     = verification.map(_.toDomain),
      archiveDownloads = archiveDownloadCount.map(_.toDomain),
      url              = url,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, AnnotatedTag] =
    toDomainAt(JsonPath.Root)

object AnnotatedTagDto:

  /** Reads an `AnnotatedTag` object. */
  given JsonDecoder[AnnotatedTagDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): AnnotatedTagDto =
    AnnotatedTagDto(
      tag                  = fields.text("tag"),
      sha                  = fields.text("sha"),
      obj                  = fields.nested("object").map(GitObjectDto.fromFields),
      message              = fields.text("message"),
      tagger               = fields.nested("tagger").map(GitIdentityDto.fromFields),
      verification         = fields.nested("verification").map(VerificationDto.fromFields),
      archiveDownloadCount = fields.nested("archive_download_count").map(ArchiveDownloadCountDto.fromFields),
      url                  = fields.text("url"),
    )
