package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.AttachmentId
import com.worxbend.codeberg4s.issues.AttachmentKind
import com.worxbend.codeberg4s.issues.IssueAttachment

/** Forgejo's `Attachment` model, field for field — the response of all ten attachment endpoints in this group.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' No golden fixture covers an issue or
  * comment attachment: the harvest was anonymous, `assets` is `[]` on all thirteen captured issues and on both captured
  * comments, and uploading requires a token. All eight declared properties are represented, and every one of them is
  * optional here per the rule `docs/HAZARDS.md` §1 states for the whole API — the spec declares no `required` on this
  * model, so an absent key and a JSON `null` are the same thing.
  *
  * `type` stays a raw string here and is folded into [[com.worxbend.codeberg4s.issues.AttachmentKind]] during
  * conversion, which is total and never fails.
  */
final case class AttachmentDto(
    id: Option[Long],
    name: Option[String],
    size: Option[Long],
    downloadCount: Option[Long],
    createdAt: Option[String],
    uuid: Option[String],
    browserDownloadUrl: Option[String],
    attachmentType: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required: `id`, which is the only way to address the attachment again and which goes through
    * [[com.worxbend.codeberg4s.issues.AttachmentId.from]] so a non-positive value is reported here rather than turning
    * into a request for `/assets/0`; and `name`, because an attachment with no file name cannot be shown.
    *
    * `size` and `download_count` default to `0` when absent, which is not the same as an empty file — see
    * [[com.worxbend.codeberg4s.issues.IssueAttachment.size]]. `type` is '''lenient''': a word this library has not
    * heard of becomes [[com.worxbend.codeberg4s.issues.AttachmentKind.Other]] rather than failing the attachment.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, IssueAttachment] =
    for
      identifier <- Wire.validated(at, "id", id)(AttachmentId.from)
      fileName   <- Wire.required(at, "name", name)
    yield IssueAttachment(
      id                 = identifier,
      name               = fileName,
      size               = size.getOrElse(0L),
      downloadCount      = downloadCount.getOrElse(0L),
      kind               = attachmentType.map(AttachmentKind.from),
      uuid               = uuid,
      browserDownloadUrl = browserDownloadUrl,
      createdAt          = Timestamps.parseOptional(createdAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, IssueAttachment] =
    toDomainAt(JsonPath.Root)

object AttachmentDto:

  /** Reads an `Attachment` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[AttachmentDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Separate from the reader so that a DTO embedding an attachment array reuses
    * the field spellings rather than repeating them.
    */
  def fromFields(fields: JsonFields): AttachmentDto =
    AttachmentDto(
      id                 = fields.number("id"),
      name               = fields.text("name"),
      size               = fields.number("size"),
      downloadCount      = fields.number("download_count"),
      createdAt          = fields.text("created_at"),
      uuid               = fields.text("uuid"),
      browserDownloadUrl = fields.text("browser_download_url"),
      attachmentType     = fields.text("type"),
    )

  /** Converts a decoded array of attachments, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[AttachmentDto]): Either[DecodeFailure, Vector[IssueAttachment]] =
    WireElements.at(base, dtos)((dto, path) => dto.toDomainAt(path))
