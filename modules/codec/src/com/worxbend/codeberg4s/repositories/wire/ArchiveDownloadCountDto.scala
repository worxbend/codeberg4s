package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.repositories.ArchiveDownloadCount

/** Forgejo's `TagArchiveDownloadCount` — the `archive_download_count` object on a tag or a release.
  *
  * Note the wire spells the gzip counter `tar_gz`, with an underscore where the file extension has a dot.
  *
  * @param zip
  *   the `zip` key
  * @param tarGz
  *   the `tar_gz` key
  */
final case class ArchiveDownloadCountDto(zip: Option[Long], tarGz: Option[Long]):

  /** Converts to the domain. Cannot fail: an absent counter is zero downloads, which is what an instance that does not
    * track them is saying.
    */
  def toDomain: ArchiveDownloadCount =
    ArchiveDownloadCount(zip = zip.getOrElse(0L), tarGz = tarGz.getOrElse(0L))

object ArchiveDownloadCountDto:

  /** Reads an `archive_download_count` object. */
  given upickle.default.Reader[ArchiveDownloadCountDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the tag and release DTOs that embed this one. */
  def fromFields(fields: JsonFields): ArchiveDownloadCountDto =
    ArchiveDownloadCountDto(
      zip   = fields.number("zip"),
      tarGz = fields.number("tar_gz"),
    )
