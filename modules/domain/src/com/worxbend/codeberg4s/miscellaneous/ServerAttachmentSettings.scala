package com.worxbend.codeberg4s.miscellaneous

/** What `GET /settings/attachment` says about uploading files to issues, comments and releases.
  *
  * Read it before offering an upload: the instance rejects an oversized or wrongly-typed attachment with a `422` whose
  * message is a raw Go string, and telling a user in advance is considerably kinder.
  *
  * @param enabled
  *   whether attachments are accepted at all. `false` when the instance omits the flag, which is the conservative
  *   reading: an instance that will not say it accepts uploads is not promised to accept them
  * @param allowedTypes
  *   the accepted content types, already split out of Forgejo's single comma-separated string. The wildcard
  *   [[ServerAttachmentSettings.AnyType]] survives as one element and means "anything" — see [[acceptsAnyType]]. Empty
  *   when the instance does not report the setting
  * @param maxSizeMib
  *   the largest single attachment, in '''mebibytes''', which is the unit Forgejo configures it in. `100` on Codeberg
  * @param maxFiles
  *   how many attachments one upload may carry
  */
final case class ServerAttachmentSettings private[codeberg4s] (
    enabled: Boolean,
    allowedTypes: Vector[String],
    maxSizeMib: Option[Long],
    maxFiles: Option[Long],
):

  /** Whether the instance accepts any content type, that is whether [[allowedTypes]] carries the wildcard
    * [[ServerAttachmentSettings.AnyType]]. Codeberg does.
    */
  def acceptsAnyType: Boolean =
    allowedTypes.contains(ServerAttachmentSettings.AnyType)

object ServerAttachmentSettings:

  /** The wildcard Forgejo puts in `allowed_types` when every content type is accepted: a star, a slash and a star. */
  val AnyType: String = "*/*"
