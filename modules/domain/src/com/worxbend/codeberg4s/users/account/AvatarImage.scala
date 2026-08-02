package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ValidationError

import scala.util.Try

import java.util.Base64

/** The image `POST /user/avatar` stores, already in the encoding that endpoint wants.
  *
  * ==This endpoint is JSON, not multipart, and that is worth stating==
  *
  * Release assets and repository avatars elsewhere in Forgejo are `multipart/form-data`, which is why
  * [[com.worxbend.codeberg4s.core.RequestBody.Multipart]] exists at all. The user avatar is not: `spec/swagger.v1.json`
  * declares the body of `userUpdateAvatar` as `UpdateUserAvatarOption`, an object with a single `image` property whose
  * description reads "image must be base64 encoded". The request is therefore an ordinary
  * [[com.worxbend.codeberg4s.core.RequestBody.Json]] carrying one string, and a caller who reaches for a multipart
  * upload is looking at the wrong endpoint.
  *
  * ==Two ways in, because callers arrive from two directions==
  *
  * [[AvatarImage.ofBytes]] takes the file a caller read from disk and encodes it, which is the common case and cannot
  * fail. [[AvatarImage.ofBase64]] takes a value that is already encoded — copied out of a data URI, or read from a
  * configuration file — and validates it, because a body carrying text that is not base64 is rejected by Forgejo with a
  * status a caller then has to interpret.
  *
  * '''No image format or size is checked here.''' Which types an instance accepts and how large an avatar may be are
  * deployment settings, and a limit this library invented would be one a caller could not raise; both arrive as a
  * `4xx`.
  *
  * The encoding is the standard base64 alphabet with padding, which is what `encoding/base64`'s `StdEncoding` — the
  * decoder Forgejo uses — expects. It is not the URL-safe alphabet.
  */
opaque type AvatarImage = String

object AvatarImage:

  /** The image bytes, encoded with the standard base64 alphabet.
    *
    * Total: every byte sequence has a base64 rendering, including the empty one — an empty image is something Forgejo
    * gets to reject, not something this library decides is impossible.
    */
  def ofBytes(bytes: Array[Byte]): AvatarImage =
    Base64.getEncoder.encodeToString(bytes)

  /** Accepts a value that is already base64.
    *
    * Trims surrounding whitespace, because an encoded blob copied out of a file routinely carries a trailing newline.
    * Rejects an empty or blank value, and rejects text that the standard base64 decoder will not read — which is the
    * whole point of this constructor, since the alternative is discovering it from a `4xx`.
    *
    * '''Line breaks inside the value are rejected.''' MIME-style base64 wraps at 76 characters and Go's `StdEncoding`
    * does not accept that, so a value carrying newlines would be refused by the instance; refusing it here says so with
    * a field name attached.
    *
    * @return
    *   the image, or a [[ValidationError]] on the `"avatarImage"` field
    */
  def ofBase64(value: String): Either[ValidationError, AvatarImage] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError("avatarImage", "must not be blank"))
    else if !isBase64(trimmed) then Left(ValidationError("avatarImage", "must be standard base64 with padding"))
    else Right(trimmed)

  extension (image: AvatarImage)

    /** The encoded image, ready to be rendered into the `image` property of the request body. */
    def base64: String = image

  /** Whether `value` reads under the standard base64 decoder.
    *
    * `Try` rather than a regular expression: the decoder is the authority on its own alphabet, padding and length
    * rules, and a pattern that agreed with it today would be a second implementation to keep in step.
    */
  private def isBase64(value: String): Boolean =
    Try(Base64.getDecoder.decode(value)).isSuccess
