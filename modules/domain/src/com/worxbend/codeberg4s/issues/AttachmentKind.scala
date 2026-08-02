package com.worxbend.codeberg4s.issues

import java.util.Locale

/** Whether an [[IssueAttachment]]'s bytes live on the instance or somewhere else.
  *
  * Derived from `spec/swagger.v1.json`: `Attachment.type` is the one field of that model the spec constrains with an
  * `enum`, and it declares exactly two values, `attachment` and `external`. No golden fixture covers it — the harvest
  * was anonymous and no captured issue carried an attachment — so this enum is the spec read literally.
  *
  * A third case exists on purpose. Forgejo may add a kind, and a caller who has already shipped must not lose an
  * attachment listing over a word this library has not heard of; [[AttachmentKind.Other]] carries that word verbatim so
  * it can be logged or matched on.
  */
enum AttachmentKind:

  /** The instance stores the bytes and serves them from [[IssueAttachment.browserDownloadUrl]]. */
  case Uploaded

  /** The attachment is a link the instance recorded; the bytes are somebody else's. */
  case External

  /** A kind this library does not know, kept verbatim rather than discarded. */
  case Other(word: String)

  /** The value this kind is spelled with on the wire. */
  def wireValue: String =
    this match
      case Uploaded    => AttachmentKind.UploadedWire
      case External    => AttachmentKind.ExternalWire
      case Other(word) => word

object AttachmentKind:

  /** The value Forgejo's `Attachment.type` uses for an uploaded attachment. */
  val UploadedWire: String = "attachment"

  /** The value Forgejo's `Attachment.type` uses for an external one. */
  val ExternalWire: String = "external"

  /** Reads the `type` field, matched case-insensitively after trimming.
    *
    * '''Total, and deliberately so.''' An unrecognised value becomes [[AttachmentKind.Other]] rather than a decoding
    * failure, for the reason the type note gives. That is why this returns a kind and not an `Either`.
    */
  def from(value: String): AttachmentKind =
    value.trim.toLowerCase(Locale.ROOT) match
      case UploadedWire => Uploaded
      case ExternalWire => External
      case other        => Other(other)
