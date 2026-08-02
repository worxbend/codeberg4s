package com.worxbend.codeberg4s.repositories.publishing

/** Everything `PATCH /repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}` may be told, as one value.
  *
  * Derived from `EditAttachmentOptions` in `spec/swagger.v1.json`, which declares two optional properties and nothing
  * required. No golden capture of this request exists.
  *
  * '''This endpoint renames an attachment; it never replaces its bytes.''' Re-uploading is a delete followed by a fresh
  * `POST`, which is two calls and two different retry decisions, so it is not hidden behind this one.
  *
  * [[EditAsset.Empty]] renders as `{}`, a well-formed request that changes nothing.
  *
  * @param name
  *   the new attachment name
  * @param browserDownloadUrl
  *   the new download URL. The spec notes this can only be set when the attachment is of the '''external''' kind — one
  *   created from a URL rather than from uploaded bytes. Sending it for an ordinary uploaded attachment is what a `4xx`
  *   from this endpoint usually means
  */
final case class EditAsset(name: Option[String], browserDownloadUrl: Option[String]):

  /** Renames the attachment. */
  def renamedTo(fileName: String): EditAsset = copy(name = Some(fileName))

  /** Repoints an '''external''' attachment at `url`; see [[browserDownloadUrl]] for the restriction. */
  def pointingAt(url: String): EditAsset = copy(browserDownloadUrl = Some(url))

  /** Whether this command would send an empty object, and therefore change nothing. */
  def isEmpty: Boolean = name.isEmpty && browserDownloadUrl.isEmpty

object EditAsset:

  /** A command that mentions nothing. Every edit starts here. */
  val Empty: EditAsset = EditAsset(name = None, browserDownloadUrl = None)
