package com.worxbend.codeberg4s.core

import java.util.Arrays

/** The payload of a request, as far as core is concerned.
  *
  * Core never imports a JSON library, so a body reaches it already serialised: the `codec` module renders a wire DTO
  * into a string and hands it over. The transport adapter is what turns a case of this type into bytes and sets the
  * `Content-Type` header.
  *
  * Every case names its own media type rather than leaving the transport to guess, because Forgejo is not uniform here:
  * `/markdown` consumes JSON while `/markdown/raw` consumes `text/plain`, and a release asset is `multipart/form-data`
  * with a named file part. Encoding those differences as separate cases keeps them visible in the request builder
  * instead of hidden in a header override.
  *
  * ==Why this is a sealed trait and not an `enum`==
  *
  * [[RequestBody.Binary]] and [[RequestBody.Multipart]] carry an `Array[Byte]`, and an array's own `equals` in Scala is
  * '''identity''' — two arrays holding the same bytes are not equal to each other. A case whose equality is to be
  * decided by the bytes therefore has to write its own `equals` and `hashCode`, and a Scala 3 `enum` case cannot have a
  * body to write them in. So the cases are ordinary `final case class`es under a sealed trait, which pattern matching,
  * construction (`RequestBody.Json("…")`) and exhaustivity checking all see exactly as they saw the enum's cases. What
  * is lost is `ordinal` and the `scala.reflect.Enum` supertype, which nothing uses.
  */
sealed trait RequestBody

/** The cases of [[RequestBody]], and the media types they are sent under. */
object RequestBody:

  /** A serialised JSON document, sent with `Content-Type: application/json`. */
  final case class Json(value: String) extends RequestBody

  /** A text body sent verbatim under `mediaType`, for endpoints that consume `text/plain` rather than JSON.
    *
    * @param value
    *   the text, sent UTF-8 encoded
    * @param mediaType
    *   the full `Content-Type` value, for example `text/plain; charset=utf-8`
    */
  final case class Text(value: String, mediaType: String) extends RequestBody

  /** Raw bytes sent under `mediaType`, for endpoints that consume a file body directly.
    *
    * The array is '''adopted, not copied''': a file body can be large, and copying it here to gain an immutability
    * guarantee the caller can already provide is the wrong trade. Do not modify an array after handing it over.
    */
  final case class Binary(bytes: Array[Byte], mediaType: String) extends RequestBody:

    /** Structural, on the media type and then on the bytes.
      *
      * Written out because the array's own `equals` is identity, which would make two bodies carrying byte-identical
      * content compare unequal — a wrong answer with no warning attached, in an assertion or in a `Set`. The media type
      * is compared first because it is the cheap half.
      *
      * The class is `final`, so no subclass can exist and the type test below is the whole of the compiler-generated
      * `canEqual`; calling `canEqual` as well would add nothing. Removing `final` would change that.
      */
    override def equals(other: Any): Boolean =
      other match
        case that: Binary => mediaType.equals(that.mediaType) && Arrays.equals(bytes, that.bytes)
        case _            => false

    override def hashCode(): Int = 31 * mediaType.hashCode + Arrays.hashCode(bytes)

  /** A single-file `multipart/form-data` body, which is how Forgejo accepts release assets and avatars.
    *
    * The transport supplies the boundary; callers only choose the part name, the file name and the bytes. The array is
    * adopted rather than copied, exactly as in [[RequestBody.Binary]].
    *
    * @param fieldName
    *   the form field name the endpoint expects, for example `attachment`
    * @param fileName
    *   the file name reported to the server
    * @param bytes
    *   the file content
    * @param mediaType
    *   the part's own content type
    */
  final case class Multipart(fieldName: String, fileName: String, bytes: Array[Byte], mediaType: String)
      extends RequestBody:

    /** Structural, on all three names and then on the bytes — see [[RequestBody.Binary.equals]] for why it is written
      * out at all, and why `canEqual` does not appear.
      */
    override def equals(other: Any): Boolean =
      other match
        case that: Multipart =>
          fieldName.equals(that.fieldName) && fileName.equals(that.fileName) && mediaType.equals(that.mediaType) &&
          Arrays.equals(bytes, that.bytes)
        case _               => false

    override def hashCode(): Int =
      31 * (31 * (31 * fieldName.hashCode + fileName.hashCode) + mediaType.hashCode) + Arrays.hashCode(bytes)

  /** A request with a body that is deliberately empty, as some Forgejo `PUT` endpoints require. */
  case object Empty extends RequestBody

  /** The media type [[RequestBody.Json]] is sent with. */
  val JsonMediaType: String = "application/json"

  /** The usual media type for [[RequestBody.Text]]; endpoints that want something narrower can say so. */
  val TextMediaType: String = "text/plain; charset=utf-8"

  /** The fallback for a file whose type the caller does not know. */
  val BinaryMediaType: String = "application/octet-stream"
