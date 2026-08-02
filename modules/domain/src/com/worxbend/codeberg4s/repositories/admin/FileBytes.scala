package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError

import scala.util.Try

import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

/** The bytes of a file on their way '''into''' a repository, already Base64-encoded.
  *
  * The write-side counterpart of [[com.worxbend.codeberg4s.repositories.FileContent]], which models what comes back
  * out. Both exist because `spec/swagger.v1.json` says the same thing on both sides — `CreateFileOptions.content` and
  * `UpdateFileOptions.content` are each described as "content must be base64 encoded" — and because a `String` that is
  * sometimes text and sometimes Base64 is exactly the kind of field a caller gets wrong once and then debugs through a
  * corrupted commit.
  *
  * ==Why this is not just a String==
  *
  * A caller who passes UTF-8 text where Base64 is expected does not get an error. Forgejo decodes whatever it was sent,
  * gets rubbish, and commits the rubbish. The repository then contains a file whose contents are the Base64 alphabet's
  * idea of the caller's text, the call reported `201`, and nothing in the round trip complained. Requiring this type is
  * how that stops being possible: [[FileBytes.ofText]] and [[FileBytes.ofBytes]] encode, and [[FileBytes.ofBase64]] is
  * the explicit "I already encoded this" door.
  *
  * The value is held encoded rather than decoded, because it is about to be written into a JSON body and decoding it
  * only to re-encode it would be work with no purpose.
  *
  * @param base64
  *   the payload exactly as it will be sent
  */
final case class FileBytes private (base64: String):

  /** The bytes this will write, or `None` when the payload cannot be decoded at all.
    *
    * '''Never throws''', and '''deliberately lenient''': decoding uses `java.util.Base64.getMimeDecoder`, which
    * tolerates the line breaks MIME Base64 permits and '''ignores''' any character outside the alphabet. So
    * `"not base64 at all!!"` decodes to whatever its alphabet characters spell rather than answering `None`; only a
    * payload whose remaining length is structurally impossible — a lone `"A"`, a bare `"===="` — does.
    *
    * This exists so a caller can sanity-check a value they built with [[FileBytes.ofBase64]]; anything built by
    * [[FileBytes.ofText]] or [[FileBytes.ofBytes]] always answers `Some`. It is not a validator, and Forgejo remains
    * the authority on what it will accept.
    */
  def decoded: Option[Array[Byte]] =
    Try(JavaBase64.getMimeDecoder.decode(base64)).toOption

  /** How many characters of Base64 will be sent. Not the size of the file — that is roughly three quarters of this. */
  def encodedLength: Int = base64.length

object FileBytes:

  /** Encodes `text` as UTF-8 and then as Base64.
    *
    * The overwhelmingly common case: writing a source file, a README, a configuration file. Cannot fail — an empty
    * string is a legitimate file, and Forgejo will happily commit one.
    */
  def ofText(text: String): FileBytes =
    ofBytes(text.getBytes(StandardCharsets.UTF_8))

  /** Encodes `bytes` as Base64.
    *
    * Cannot fail. The encoding is what `java.util.Base64.getEncoder` produces — one unbroken line, padded — which is
    * what Forgejo's Go decoder expects.
    */
  def ofBytes(bytes: Array[Byte]): FileBytes =
    FileBytes(JavaBase64.getEncoder.encodeToString(bytes))

  /** Takes Base64 the caller has already produced.
    *
    * '''The one door that trusts its input.''' The payload is not decoded to check it: a caller who encoded it
    * themselves knows the encoding they used, and streaming a large blob through a decoder here to catch a mistake
    * Forgejo reports anyway would double the cost of every write. Only a blank value is rejected — that one is always a
    * bug, since an empty '''file''' is `ofText("")`, whose Base64 is also the empty string but arrives with the caller
    * having said so.
    *
    * @return
    *   the payload, or a [[ValidationError]] on the `"fileContent"` field
    */
  def ofBase64(value: String): Either[ValidationError, FileBytes] =
    if value.trim.isEmpty then Left(ValidationError("fileContent", "must not be blank"))
    else Right(FileBytes(value.trim))
