package com.worxbend.codeberg4s.issues

import java.time.Instant

/** A file attached to an issue or to a comment.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Forgejo's `Attachment` model is what all
  * ten attachment endpoints in this group return, and the golden harvest was anonymous: none of the thirteen captured
  * issues carried an attachment, and `assets` is `[]` on every one of them. The field set, the `type` vocabulary and
  * the nullability treatment here are the spec read literally under the conservative rule `docs/HAZARDS.md` §1 states
  * for the whole API.
  *
  * Forgejo uses this same model for a release asset, which `com.worxbend.codeberg4s.repositories.ReleaseAsset` already
  * covers. The two are '''not''' merged: that one is owned by the publishing group per `docs/LEDGER.md`, it is
  * addressed by a different identifier type, and it does not carry [[kind]]. Sharing a model across two ownership
  * boundaries to save a dozen lines would make either group's next field the other group's problem.
  *
  * ==Reading the bytes==
  *
  * This library returns metadata only. [[browserDownloadUrl]] is the supported route to the content: hand it to an HTTP
  * client that can stream, exactly as `com.worxbend.codeberg4s.repositories.actions.ActionArtifact.archiveDownloadUrl`
  * does. Nothing here downloads, because [[com.worxbend.codeberg4s.core.CodebergResponse]] carries a body as `String`
  * and an arbitrary file is not text.
  *
  * @param id
  *   the instance-wide identifier, and the only way to address the attachment again; see [[AttachmentId]]
  * @param name
  *   the stored file name
  * @param size
  *   the size in bytes as the instance reports it; `0` when it did not say, which is not the same as an empty file
  * @param downloadCount
  *   how many times the instance has served it; `0` when it did not say
  * @param kind
  *   whether the bytes are on the instance or elsewhere; see [[AttachmentKind]]
  * @param uuid
  *   the opaque identifier Forgejo puts in the download URL, absent when it did not send one
  * @param browserDownloadUrl
  *   where a browser — or a streaming HTTP client — fetches the content
  * @param createdAt
  *   when the attachment was uploaded
  */
final case class IssueAttachment(
    id: AttachmentId,
    name: String,
    size: Long,
    downloadCount: Long,
    kind: Option[AttachmentKind],
    uuid: Option[String],
    browserDownloadUrl: Option[String],
    createdAt: Option[Instant],
)
