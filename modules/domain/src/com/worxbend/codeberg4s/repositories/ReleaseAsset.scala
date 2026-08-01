package com.worxbend.codeberg4s.repositories

import java.time.Instant

/** A file attached to a [[Release]] — a built binary, a checksum, a signature.
  *
  * Distinct from the source archives Forgejo generates from the tag: those are [[Release.zipballUrl]] and
  * [[Release.tarballUrl]], and their counters are [[Release.archiveDownloads]].
  *
  * Every asset in `golden/repository/releases-list.json` reports `"type": "attachment"`, the only value Forgejo emits
  * here, so the field is dropped on the way into this model rather than modelled as a constant.
  *
  * @param id
  *   the instance-local identifier of the attachment
  * @param name
  *   the file name, for example `forgejo-16.0.2-linux-amd64`
  * @param size
  *   the size in bytes
  * @param downloadCount
  *   how often this asset has been downloaded
  * @param createdAt
  *   when it was uploaded
  * @param uuid
  *   the storage identifier; stable, and what the download URL is built from
  * @param browserDownloadUrl
  *   where the asset can be fetched. Downloading it is outside this library: the body is arbitrarily large and belongs
  *   in a stream, not in a `String`
  */
final case class ReleaseAsset(
    id: Long,
    name: String,
    size: Long,
    downloadCount: Long,
    createdAt: Option[Instant],
    uuid: Option[String],
    browserDownloadUrl: Option[String],
)
