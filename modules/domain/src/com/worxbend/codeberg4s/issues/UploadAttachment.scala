package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ContentType
import com.worxbend.codeberg4s.ValidationError

import java.time.Instant

/** A file to attach to an issue or to a comment — the `multipart/form-data` half of
  * `POST /repos/{owner}/{repo}/issues/{index}/assets` and of the matching comment route.
  *
  * '''Derived from `spec/swagger.v1.json`.''' Both operations declare a form part called `attachment` of type `file`,
  * plus two query parameters, `name` and `updated_at`. No golden capture of an upload exists — the harvest could only
  * read — so the request shape here is the spec's.
  *
  * ==Two names, and they are not the same name==
  *
  * [[fileName]] is what goes in the part's `Content-Disposition`. [[storedName]] is the optional `name` query
  * parameter, which is what Forgejo records as the attachment's name when it is present. Setting only [[fileName]] is
  * the ordinary case and makes the two agree; setting [[storedName]] as well is how a caller uploads
  * `build/logs/run.txt` from disk and has it appear as `failing-run.txt`.
  *
  * ==Aliasing==
  *
  * [[content]] is '''not''' copied, here or at the transport boundary, for the reason
  * `com.worxbend.codeberg4s.repositories.publishing.UploadAsset` gives: an attachment can be large and copying it to
  * gain an immutability guarantee the caller can already provide is the wrong trade. A caller must therefore not mutate
  * the array after handing it over. For the same reason the generated `equals` compares [[content]] by reference, so
  * two structurally identical uploads are not equal; nothing in this library depends on that.
  *
  * ==Construction==
  *
  * The constructor is private, so [[UploadAttachment.of]] is the only way to obtain one and the checks it performs
  * cannot be stepped around by calling the generated `apply` or `copy`. Reading the fields and pattern matching are
  * unaffected.
  *
  * ==Error contract==
  *
  * [[UploadAttachment.of]] produces a [[ValidationError]] on the `"fileName"` field, [[as]] one on the `"mediaType"`
  * field, and nothing else here can fail. No member performs I/O or reads a file. Turning a path into bytes is the
  * caller's job, deliberately: this library owns no filesystem effect.
  *
  * @param fileName
  *   the file name announced in the multipart part
  * @param content
  *   the bytes to upload
  * @param mediaType
  *   the part's own `Content-Type`; [[UploadAttachment.DefaultMediaType]] unless the caller knows better
  * @param storedName
  *   the `name` query parameter, when the recorded name should differ from [[fileName]]
  * @param updatedAt
  *   the `updated_at` query parameter, which an import uses to backdate the attachment
  */
final case class UploadAttachment private (
    fileName: String,
    content: Array[Byte],
    mediaType: String,
    storedName: Option[String],
    updatedAt: Option[Instant],
):

  /** Records the attachment under `attachment` instead of under [[fileName]]. */
  def named(attachment: String): UploadAttachment = copy(storedName = Some(attachment))

  /** Declares the part's own `Content-Type`, for an instance or a proxy that acts on it.
    *
    * The value is written into the multipart body as a header, so it is checked the way [[fileName]] is: trimmed, then
    * refused when it is blank or carries a control character. A carriage return or a newline in it would end the part's
    * header line and let whatever follows be read as headers of the caller's choosing.
    *
    * @return
    *   the upload sent under `media`, or a [[ValidationError]] on the `"mediaType"` field
    */
  def as(media: String): Either[ValidationError, UploadAttachment] =
    ContentType.from("mediaType", media).map(checked => copy(mediaType = checked))

  /** Backdates the attachment, which is what an importer wants and nothing else does. */
  def recordedAt(moment: Instant): UploadAttachment = copy(updatedAt = Some(moment))

  /** How many bytes would be sent. */
  def size: Int = content.length

object UploadAttachment:

  /** The fallback content type for a file whose type the caller does not know.
    *
    * Spelled out here rather than borrowed from `core.RequestBody.BinaryMediaType`, because the domain module depends
    * on nothing — that is the rule the module graph enforces — and the two are asserted to agree by the test suite
    * rather than by an import.
    */
  val DefaultMediaType: String = "application/octet-stream"

  /** Characters a file name may not contain, because it is written into a `Content-Disposition` header.
    *
    * A quotation mark closes the `filename="…"` parameter and a carriage return or newline ends the header line
    * outright, so either would let a caller-supplied file name inject a header of its own into the multipart body. That
    * is a request-smuggling boundary, which is why the check is in a smart constructor a caller cannot bypass rather
    * than at the transport.
    */
  private val Forbidden: String = "\"\r\n"

  /** Starts an upload from a file name and its bytes.
    *
    * Trims the file name. Rejects a blank one, one containing a quotation mark, a carriage return or a newline, and one
    * containing any other control character. `/` is '''allowed''': a file name is not a path segment here, it is a
    * quoted header parameter.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"fileName"` field
    */
  def of(fileName: String, content: Array[Byte]): Either[ValidationError, UploadAttachment] =
    val trimmed = fileName.trim
    if trimmed.isEmpty then Left(ValidationError("fileName", "must not be blank"))
    else if trimmed.exists(Forbidden.contains) then
      Left(ValidationError("fileName", "must not contain a quotation mark or a line break"))
    else if trimmed.exists(_.isControl) then
      Left(ValidationError("fileName", "must not contain a control character"))
    else
      Right(
        UploadAttachment(
          fileName   = trimmed,
          content    = content,
          mediaType  = DefaultMediaType,
          storedName = None,
          updatedAt  = None,
        )
      )
