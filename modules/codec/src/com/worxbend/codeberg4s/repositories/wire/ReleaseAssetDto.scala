package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.ReleaseAsset

/** Forgejo's `Attachment` — one element of a release's `assets` array.
  *
  * @param id
  *   the `id` key
  * @param name
  *   the `name` key
  * @param size
  *   the `size` key, in bytes
  * @param downloadCount
  *   the `download_count` key
  * @param createdAt
  *   the `created_at` key as a raw string
  * @param uuid
  *   the `uuid` key
  * @param browserDownloadUrl
  *   the `browser_download_url` key
  * @param assetType
  *   the `type` key, renamed because `type` is a Scala keyword. Every asset in `golden/repository/releases-list.json`
  *   reports `attachment`, and the domain model drops the field rather than carry a constant
  */
final case class ReleaseAssetDto(
    id: Option[Long],
    name: Option[String],
    size: Option[Long],
    downloadCount: Option[Long],
    createdAt: Option[String],
    uuid: Option[String],
    browserDownloadUrl: Option[String],
    assetType: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `id` and `name`: an attachment with neither an identity nor a file name cannot be shown or fetched. An
    * absent `size` or `download_count` becomes `0`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ReleaseAsset] =
    for
      identifier <- Wire.required(at, "id", id)
      fileName   <- Wire.required(at, "name", name)
    yield ReleaseAsset(
      id                 = identifier,
      name               = fileName,
      size               = size.getOrElse(0L),
      downloadCount      = downloadCount.getOrElse(0L),
      createdAt          = Timestamps.parseOptional(createdAt),
      uuid               = uuid,
      browserDownloadUrl = browserDownloadUrl,
    )

object ReleaseAssetDto:

  /** Reads one element of an `assets` array. */
  given JsonDecoder[ReleaseAssetDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the release DTO that embeds these. */
  def fromFields(fields: JsonFields): ReleaseAssetDto =
    ReleaseAssetDto(
      id                 = fields.number("id"),
      name               = fields.text("name"),
      size               = fields.number("size"),
      downloadCount      = fields.number("download_count"),
      createdAt          = fields.text("created_at"),
      uuid               = fields.text("uuid"),
      browserDownloadUrl = fields.text("browser_download_url"),
      assetType          = fields.text("type"),
    )
