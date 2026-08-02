package com.worxbend.codeberg4s.issues

/** What `PATCH …/assets/{attachment_id}` may be told about an existing [[IssueAttachment]].
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `EditAttachmentOptions`, which declares exactly two
  * properties. No golden capture of this request exists.
  *
  * '''Only what the caller set is emitted, and `browser_download_url` in particular must not appear unasked.''' The
  * spec notes that field may only be set on an attachment of the external kind, so sending it by default would turn
  * every rename of an ordinary uploaded attachment into a rejected request. That is why [[browserDownloadUrl]] is an
  * `Option` rather than a `String` with an empty default.
  *
  * An [[EditAttachment.Empty]] sent as-is is a well-formed request that changes nothing; it is not rejected here, for
  * the reason [[EditIssue]] gives.
  *
  * @param name
  *   the new stored file name
  * @param browserDownloadUrl
  *   the new download URL; accepted by Forgejo only on an attachment of [[AttachmentKind.External]]
  */
final case class EditAttachment(name: Option[String], browserDownloadUrl: Option[String]):

  /** Renames the attachment. */
  def renamedTo(fileName: String): EditAttachment = copy(name = Some(fileName))

  /** Repoints an external attachment at `url`; see the class note on when Forgejo accepts this. */
  def pointingAt(url: String): EditAttachment = copy(browserDownloadUrl = Some(url))

object EditAttachment:

  /** An edit that changes nothing — the starting point for every attachment edit. */
  val Empty: EditAttachment = EditAttachment(name = None, browserDownloadUrl = None)
