package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.ContentType
import com.worxbend.codeberg4s.ValidationError

/** A file to attach to a release — the `multipart/form-data` half of `POST /repos/{owner}/{repo}/releases/{id}/assets`.
  *
  * Derived from that operation's parameters in `spec/swagger.v1.json`: a `name` query parameter, and a form part called
  * `attachment` of type `file`. No golden capture of an upload exists — the harvest was anonymous and could only read —
  * so the '''request''' shape here is the spec's, while the '''response''' is an `Attachment`, whose shape is captured
  * as the elements of `assets` in `golden/repository/release-latest.json`.
  *
  * ==Two names, and they are not the same name==
  *
  * [[fileName]] is what goes in the part's `Content-Disposition`. [[name]] is the optional `name` query parameter,
  * which is what Forgejo stores as the attachment's name when it is present. Setting only [[fileName]] is the ordinary
  * case and makes the two agree; setting [[name]] as well is how a caller uploads `build/out.tar.gz` from disk and has
  * it appear as `forgejo-16.0.2-linux-amd64`.
  *
  * ==Aliasing==
  *
  * [[content]] is '''not''' copied, here or at the transport boundary, because a release asset is routinely hundreds of
  * megabytes — the first element of `golden/repository/release-latest.json` is 119 MB — and copying it twice to gain an
  * immutability guarantee the caller can already provide is the wrong trade. A caller must therefore not mutate the
  * array after handing it over. For the same reason the generated `equals` compares [[content]] by reference, so two
  * structurally identical uploads are not equal; nothing in this library depends on that.
  *
  * ==Construction==
  *
  * The constructor is private, so [[UploadAsset.of]] is the only way to obtain one and the checks it performs cannot be
  * stepped around by calling the generated `apply` or `copy`. Reading the fields and pattern matching are unaffected.
  *
  * ==Error contract==
  *
  * [[UploadAsset.of]] produces a [[ValidationError]] on the `"fileName"` field, [[as]] one on the `"mediaType"` field,
  * and nothing else here can fail. No member performs I/O or reads a file. Turning a path into bytes is the caller's
  * job, and deliberately so: this library owns no filesystem effect.
  *
  * @param fileName
  *   the file name announced in the multipart part
  * @param content
  *   the bytes to upload
  * @param mediaType
  *   the part's own `Content-Type`; [[UploadAsset.DefaultMediaType]] unless the caller knows better
  * @param name
  *   the `name` query parameter, when the stored name should differ from [[fileName]]
  */
final case class UploadAsset private (fileName: String, content: Array[Byte], mediaType: String, name: Option[String]):

  /** Stores the attachment under `attachment` instead of under [[fileName]]. */
  def named(attachment: String): UploadAsset = copy(name = Some(attachment))

  /** Declares the part's own `Content-Type`, for an instance or a proxy that acts on it.
    *
    * The value is written into the multipart body as a header, so it is checked the way [[fileName]] is: trimmed, then
    * refused when it is blank or carries a control character. A carriage return or a newline in it would end the part's
    * header line and let whatever follows be read as headers of the caller's choosing.
    *
    * @return
    *   the upload sent under `media`, or a [[ValidationError]] on the `"mediaType"` field
    */
  def as(media: String): Either[ValidationError, UploadAsset] =
    ContentType.from("mediaType", media).map(checked => copy(mediaType = checked))

  /** How many bytes would be sent. */
  def size: Int = content.length

object UploadAsset:

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
    * is a request-smuggling boundary, which is why the check is here — in a smart constructor a caller cannot bypass —
    * rather than at the transport.
    */
  private val Forbidden: String = "\"\r\n"

  /** Starts an upload from a file name and its bytes.
    *
    * Trims the file name. Rejects a blank one, one containing a quotation mark, a carriage return or a newline, and one
    * containing any other control character. `/` is '''allowed''': a file name is not a path segment here, it is a
    * quoted header parameter, and `linux/amd64.tar.gz` is a name a caller may legitimately want.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"fileName"` field
    */
  def of(fileName: String, content: Array[Byte]): Either[ValidationError, UploadAsset] =
    val trimmed = fileName.trim
    if trimmed.isEmpty then Left(ValidationError("fileName", "must not be blank"))
    else if trimmed.exists(Forbidden.contains) then
      Left(ValidationError("fileName", "must not contain a quotation mark or a line break"))
    else if trimmed.exists(_.isControl) then
      Left(ValidationError("fileName", "must not contain a control character"))
    else Right(UploadAsset(fileName = trimmed, content = content, mediaType = DefaultMediaType, name = None))
