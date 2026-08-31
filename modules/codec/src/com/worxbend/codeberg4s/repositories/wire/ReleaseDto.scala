package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.{Release, ReleaseId, TagName}
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Release` model, field for field.
  *
  * Every key on `golden/repository/release-latest.json` and on the two elements of
  * `golden/repository/releases-list.json` is represented.
  *
  * @param id
  *   the `id` key
  * @param tagName
  *   the `tag_name` key
  * @param targetCommitish
  *   the `target_commitish` key
  * @param name
  *   the `name` key: the release title
  * @param body
  *   the `body` key: the release notes, in Markdown
  * @param url
  *   the `url` key
  * @param htmlUrl
  *   the `html_url` key
  * @param tarballUrl
  *   the `tarball_url` key
  * @param zipballUrl
  *   the `zipball_url` key
  * @param hideArchiveLinks
  *   the `hide_archive_links` key
  * @param uploadUrl
  *   the `upload_url` key
  * @param draft
  *   the `draft` key
  * @param prerelease
  *   the `prerelease` key
  * @param createdAt
  *   the `created_at` key as a raw string
  * @param publishedAt
  *   the `published_at` key as a raw string
  * @param author
  *   the `author` key
  * @param assets
  *   the `assets` key; empty when absent, `null`, or an empty array
  * @param archiveDownloadCount
  *   the `archive_download_count` key
  */
final case class ReleaseDto(
    id: Option[Long],
    tagName: Option[String],
    targetCommitish: Option[String],
    name: Option[String],
    body: Option[String],
    url: Option[String],
    htmlUrl: Option[String],
    tarballUrl: Option[String],
    zipballUrl: Option[String],
    hideArchiveLinks: Option[Boolean],
    uploadUrl: Option[String],
    draft: Option[Boolean],
    prerelease: Option[Boolean],
    createdAt: Option[String],
    publishedAt: Option[String],
    author: Option[UserDto],
    assets: Vector[ReleaseAssetDto],
    archiveDownloadCount: Option[ArchiveDownloadCountDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `id` and `tag_name`, both through their smart constructors: the id is what `GET /releases/{id}` takes and
    * the tag is what the release is attached to. Absent flags become `false`, which is the published, non-draft,
    * non-prerelease reading — the one a caller filtering for visible releases wants when the instance says nothing.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Release] =
    for
      identifier <- Wire.validated(at, "id", id)(ReleaseId.from)
      tag        <- Wire.validated(at, "tag_name", tagName)(TagName.from)
      publisher  <- Wire.nested(at, "author", author)(_.toDomainAt(_))
      attached   <- ArrayElements.convert(at.field("assets"), assets)((dto, path) => dto.toDomainAt(path))
    yield Release(
      id                = identifier,
      tagName           = tag,
      targetCommitish   = targetCommitish,
      name              = name,
      body              = body,
      url               = url,
      htmlUrl           = htmlUrl,
      tarballUrl        = tarballUrl,
      zipballUrl        = zipballUrl,
      uploadUrl         = uploadUrl,
      isDraft           = draft.getOrElse(false),
      isPrerelease      = prerelease.getOrElse(false),
      hidesArchiveLinks = hideArchiveLinks.getOrElse(false),
      createdAt         = Timestamps.parseOptional(createdAt),
      publishedAt       = Timestamps.parseOptional(publishedAt),
      author            = publisher,
      assets            = attached,
      archiveDownloads  = archiveDownloadCount.map(_.toDomain),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Release] =
    toDomainAt(JsonPath.Root)

object ReleaseDto:

  /** Reads a `Release` object. */
  given JsonDecoder[ReleaseDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ReleaseDto =
    ReleaseDto(
      id                   = fields.number("id"),
      tagName              = fields.text("tag_name"),
      targetCommitish      = fields.text("target_commitish"),
      name                 = fields.text("name"),
      body                 = fields.text("body"),
      url                  = fields.text("url"),
      htmlUrl              = fields.text("html_url"),
      tarballUrl           = fields.text("tarball_url"),
      zipballUrl           = fields.text("zipball_url"),
      hideArchiveLinks     = fields.boolean("hide_archive_links"),
      uploadUrl            = fields.text("upload_url"),
      draft                = fields.boolean("draft"),
      prerelease           = fields.boolean("prerelease"),
      createdAt            = fields.text("created_at"),
      publishedAt          = fields.text("published_at"),
      author               = fields.nested("author").map(UserDto.fromFields),
      assets               = fields.nestedAll("assets").map(ReleaseAssetDto.fromFields),
      archiveDownloadCount = fields.nested("archive_download_count").map(ArchiveDownloadCountDto.fromFields),
    )
