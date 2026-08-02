package com.worxbend.codeberg4s.core

/** The payload of a request, as far as core is concerned.
  *
  * Core never imports a JSON library, so a body reaches it already serialised: the `codec` module renders a wire DTO
  * into a string and hands it over. The transport adapter is what turns a case of this enum into bytes and sets the
  * `Content-Type` header.
  *
  * Every case names its own media type rather than leaving the transport to guess, because Forgejo is not uniform here:
  * `/markdown` consumes JSON while `/markdown/raw` consumes `text/plain`, and a release asset is `multipart/form-data`
  * with a named file part. Encoding those differences as separate cases keeps them visible in the request builder
  * instead of hidden in a header override.
  */
enum RequestBody:

  /** A serialised JSON document, sent with `Content-Type: application/json`. */
  case Json(value: String)

  /** A text body sent verbatim under `mediaType`, for endpoints that consume `text/plain` rather than JSON.
    *
    * @param value
    *   the text, sent UTF-8 encoded
    * @param mediaType
    *   the full `Content-Type` value, for example `text/plain; charset=utf-8`
    */
  case Text(value: String, mediaType: String)

  /** Raw bytes sent under `mediaType`, for endpoints that consume a file body directly. */
  case Binary(bytes: Array[Byte], mediaType: String)

  /** A single-file `multipart/form-data` body, which is how Forgejo accepts release assets and avatars.
    *
    * The transport supplies the boundary; callers only choose the part name, the file name and the bytes.
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
  case Multipart(fieldName: String, fileName: String, bytes: Array[Byte], mediaType: String)

  /** A request with a body that is deliberately empty, as some Forgejo `PUT` endpoints require. */
  case Empty

object RequestBody:

  /** The media type [[RequestBody.Json]] is sent with. */
  val JsonMediaType: String = "application/json"

  /** The usual media type for [[RequestBody.Text]]; endpoints that want something narrower can say so. */
  val TextMediaType: String = "text/plain; charset=utf-8"

  /** The fallback for a file whose type the caller does not know. */
  val BinaryMediaType: String = "application/octet-stream"
