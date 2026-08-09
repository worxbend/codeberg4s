package com.worxbend.codeberg4s.core

import scala.annotation.tailrec
import scala.util.Try

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale

/** A response body exactly as it came off the socket — the bytes, and the charset the response declared for them.
  *
  * '''Why bytes and not a `String`.''' Text was the wrong currency for this library. A transport that hands over a
  * `String` has already decoded the payload once, and the JSON parser then encodes that `String` straight back into a
  * `byte[]` to read it, because every JSON parser worth using reads bytes. That is two full copies of a response before
  * a single field is looked at — on a 170 KB listing page, a third of a megabyte of garbage per call. Carrying the
  * bytes and decoding to text only where text is genuinely wanted removes both copies from the JSON path, which is
  * every path but a handful.
  *
  * '''The charset is read, not assumed.''' [[charset]] is whatever the response's `Content-Type` declared, falling back
  * to UTF-8 when it declared nothing, declared something unparseable, or declared a charset this JVM does not have —
  * which is precisely what the sttp adapter used to do on this library's behalf, so nothing about text decoding changed
  * when the body stopped being text. Forgejo sends `charset=utf-8` on everything, but that is an observation about one
  * server rather than a licence to ignore the header, and [[utf8Bytes]] states the one place where UTF-8 is genuinely
  * required rather than merely expected.
  *
  * '''Ownership of the array.''' [[bytes]] hands back the array this body holds, without copying it. Copying would
  * reintroduce the very copy this type exists to remove, so the contract is the other way round: whoever constructs a
  * `ResponseBody` gives up the array, and whoever reads [[bytes]] must not modify it. Every reader in this library
  * obeys that — jsoniter reads the array and never writes to it.
  *
  * Instances are immutable as long as that contract is kept, and are safe to share between threads.
  *
  * @param charset
  *   the charset [[text]] decodes with, taken from the response's `Content-Type`
  */
final class ResponseBody private (private val raw: Array[Byte], val charset: Charset):

  /** The body verbatim, '''not copied'''. Read it; do not write to it. See the note on ownership above. */
  def bytes: Array[Byte] = raw

  /** How many bytes the body holds. Cheaper than decoding it, and the thing worth logging. */
  def size: Int = raw.length

  /** Whether the server sent no body at all — a `204`, or a `200` with nothing after the headers. */
  def isEmpty: Boolean = raw.isEmpty

  /** Whether the body is empty or contains nothing but ASCII whitespace.
    *
    * Answered on the bytes, so asking it of a 40 MB payload does not decode 40 MB of text. That makes it very slightly
    * stricter than `String.isBlank`, which also counts a handful of non-ASCII Unicode separators such as `U+2028`: a
    * body made only of those is reported here as non-blank. The consequence of that difference is that such a body is
    * handed to the parser and fails there, rather than being silently treated as absent — the safe direction, and no
    * server this library talks to has ever sent one.
    */
  def isBlank: Boolean = ResponseBody.allWhitespace(raw, 0)

  /** The whole body decoded with [[charset]].
    *
    * Bytes that are not valid in that charset become the replacement character rather than an error, because a body is
    * only ever decoded here for something a human will read — an error payload, a `text/plain` endpoint, a failure
    * excerpt — and failing to render an explanation would replace a useful message with a useless one.
    *
    * Decoded once and kept: the error path reads it to parse the payload and again to build the excerpt.
    */
  lazy val text: String = String(raw, charset)

  /** The body as UTF-8 bytes, for a reader that requires UTF-8 rather than merely expecting it.
    *
    * JSON is the case. RFC 8259 §8.1 says JSON exchanged between systems that are not one closed ecosystem '''must'''
    * be encoded as UTF-8, and every JSON parser this library could use reads UTF-8 bytes and nothing else. So the JSON
    * path asks for this rather than for [[bytes]], and the check is written down here instead of being assumed
    * anywhere: when the response really did declare UTF-8 — which is what Forgejo declares, on every endpoint — this is
    * [[bytes]] and costs nothing, and when it declared something else the body is transcoded through [[text]] so that a
    * non-conforming server is read correctly rather than as mojibake.
    */
  def utf8Bytes: Array[Byte] =
    if charset.equals(StandardCharsets.UTF_8) then raw else text.getBytes(StandardCharsets.UTF_8)

  /** An excerpt of the decoded body no longer than `maxChars` '''characters'''.
    *
    * Characters, not bytes, and that distinction is the whole reason this lives here. Slicing the byte array to a fixed
    * length and decoding the slice would cut a multi-byte character in half and end the excerpt in a replacement
    * character that the server never sent. So the slice is deliberately generous — enough bytes that `maxChars`
    * characters are certainly inside it, computed from the widest encoding of one character in this charset — and the
    * bound is then applied to the decoded text, where a character is a character. Any damage the generous slice did at
    * its own tail sits beyond `maxChars` and is discarded with the rest.
    *
    * The point of the bound is that a decoding failure on a 40 MB listing must not put 40 MB into an error value an
    * application is about to log. Slicing first is what keeps that promise for the decode as well as for the result.
    *
    * @param maxChars
    *   the longest excerpt wanted; zero or less yields an empty string
    */
  def excerpt(maxChars: Int): String =
    if maxChars <= 0 || raw.isEmpty then ""
    else
      val enough = math.min(raw.length.toLong, (maxChars.toLong + 1L) * widestCharInBytes).toInt
      String(raw, 0, enough, charset).take(maxChars)

  /** Structural, on the bytes and the charset. Two bodies holding equal bytes decode to the same text only if they
    * declare the same charset, so both halves count. Written out because the array's own `equals` is identity, which
    * would make every response value compare unequal to an identical one and quietly break anybody's tests.
    */
  override def equals(other: Any): Boolean =
    other match
      case that: ResponseBody => charset.equals(that.charset) && Arrays.equals(raw, that.raw)
      case _                  => false

  override def hashCode(): Int = 31 * Arrays.hashCode(raw) + charset.hashCode

  /** Deliberately does not render the body. A response payload can carry a freshly minted access token, and this
    * library's failures are logged.
    */
  override def toString: String = s"ResponseBody($size B, ${charset.name})"

  /** The widest one character can be in this charset, in bytes — the factor [[excerpt]] slices by.
    *
    * `maxBytesPerChar` is the encoder's own guarantee, so it is an upper bound for the decoding direction too: UTF-8
    * reports 3, and its 4-byte sequences produce two characters, which is 2 bytes per character. A charset that cannot
    * encode at all reports nothing, and 4 is above every charset in the JDK.
    */
  private def widestCharInBytes: Int =
    if charset.canEncode then math.max(1, math.ceil(charset.newEncoder().maxBytesPerChar().toDouble).toInt)
    else ResponseBody.WidestCharFallback

/** How a [[ResponseBody]] is built, and how a declared charset is read. */
object ResponseBody:

  /** No body at all: zero bytes, nominally UTF-8. What a `204` carries. */
  val Empty: ResponseBody = ResponseBody(Array.emptyByteArray, StandardCharsets.UTF_8)

  /** Bytes off the wire, with the charset the response declared for them.
    *
    * '''The array is adopted, not copied.''' The caller must not keep a reference it later writes through. See the
    * ownership note on [[ResponseBody]] for why copying here would defeat the purpose of the type.
    */
  def of(bytes: Array[Byte], charset: Charset): ResponseBody = ResponseBody(bytes, charset)

  /** A body written as text and encoded as UTF-8 — for a test fake, or for anyone standing in for a transport. */
  def utf8(text: String): ResponseBody =
    ResponseBody(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)

  /** The charset a `Content-Type` declared, or UTF-8.
    *
    * UTF-8 is the answer for a header that is absent, carries no `charset` parameter, carries one that is not a legal
    * charset name, or names a charset this JVM does not provide. None of those is worth failing a whole request over,
    * and UTF-8 is both the RFC 8259 requirement for JSON and what every Forgejo endpoint actually declares. The
    * parameter name is matched case-insensitively and a quoted value is unquoted, as RFC 9110 §5.6.6 allows.
    */
  def charsetOf(contentType: Option[String]): Charset =
    contentType
      .flatMap(declaredCharset)
      .flatMap(supported)
      .getOrElse(StandardCharsets.UTF_8)

  /** Above every `maxBytesPerChar` in the JDK, used when a charset cannot encode and so reports none. */
  private val WidestCharFallback: Int = 4

  private val CharsetParameter: String = "charset="

  private val Quote: String = "\""

  private def declaredCharset(contentType: String): Option[String] =
    contentType
      .split(';')
      .iterator
      .drop(1)
      .map(_.trim)
      .find(_.toLowerCase(Locale.ROOT).startsWith(CharsetParameter))
      .map(parameter => unquoted(parameter.drop(CharsetParameter.length).trim))
      .filter(_.nonEmpty)

  private def unquoted(value: String): String =
    if value.length >= 2 && value.startsWith(Quote) && value.endsWith(Quote) then
      value.substring(1, value.length - 1)
    else value

  private def supported(name: String): Option[Charset] = Try(Charset.forName(name)).toOption

  /** ASCII whitespace only, so that [[ResponseBody.isBlank]] never decodes the body. */
  @tailrec
  private def allWhitespace(bytes: Array[Byte], index: Int): Boolean =
    if index >= bytes.length then true
    else if isAsciiWhitespace(bytes(index)) then allWhitespace(bytes, index + 1)
    else false

  /** Space, tab, line feed, vertical tab, form feed and carriage return — the ASCII characters `String.isBlank` counts,
    * as their byte values.
    */
  private def isAsciiWhitespace(byte: Byte): Boolean =
    byte match
      case 32 | 9 | 10 | 11 | 12 | 13 => true
      case _ => false
