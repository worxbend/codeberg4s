package com.worxbend.codeberg4s.repositories

import scala.util.Try

import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

/** The bytes of a file, together with how Forgejo encoded them for transport.
  *
  * Forgejo has only ever been observed sending `base64` — `golden/repository/contents-file.json` is a base64 payload —
  * but the field exists on the wire, so an instance sending something else is a possibility the type has to survive.
  * [[Opaque]] is that survival: the payload is preserved verbatim and [[decoded]] answers `None`, rather than the
  * library guessing an encoding and handing back rubbish.
  *
  * The bytes are held encoded rather than decoded on the way in, because decoding a 10 MB blob that the caller only
  * wanted the size of is work nobody asked for. [[decoded]] and [[text]] are methods, not fields, for the same reason.
  */
enum FileContent:

  /** Base64, which is what Forgejo sends. */
  case Base64(raw: String)

  /** An encoding this library does not implement, kept verbatim so nothing is lost.
    *
    * @param encoding
    *   the instance's own name for it, absent when it sent content without naming an encoding
    */
  case Opaque(encoding: Option[String], raw: String)

  /** The payload exactly as the instance sent it, still encoded. */
  def raw: String

  /** The file's bytes, or `None` when the encoding is one this library does not implement.
    *
    * '''Never throws.''' A payload that claims to be base64 and is not answers `None` too. Allocates a fresh array on
    * every call; hold the result if you need it twice.
    *
    * Line breaks inside the payload are tolerated, since MIME base64 permits them and nothing in Forgejo's contract
    * promises their absence.
    */
  def decoded: Option[Array[Byte]] =
    this match
      case Base64(payload) => Try(JavaBase64.getMimeDecoder.decode(payload)).toOption
      case Opaque(_, _)    => None

  /** The file's bytes read as UTF-8 text, or `None` when [[decoded]] is `None`.
    *
    * Does '''not''' check that the file is text: a binary blob answers `Some` full of replacement characters, because
    * that is what decoding arbitrary bytes as UTF-8 produces. Check the file yourself if it might not be text.
    */
  def text: Option[String] =
    decoded.map(bytes => String(bytes, StandardCharsets.UTF_8))
