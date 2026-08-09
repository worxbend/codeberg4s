package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.syntax.discard

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Renders a request in a form that is safe to show a human.
  *
  * Every URI that reaches a [[com.worxbend.codeberg4s.CallContext]], and therefore every URI that can appear in an
  * error message, a telemetry callback or an application's log, comes from here. That makes this object a security
  * boundary rather than a formatting helper: if a credential can survive [[Redaction.uri]], it can reach a log file.
  *
  * Forgejo accepts a token as a query parameter as well as in a header. This library never sends one that way, but a
  * caller can add an arbitrary query parameter, so the sensitive names are masked on the way out regardless.
  */
object Redaction:

  /** The constant that replaces credential material. Never percent-encoded, so it stays readable. */
  val Mask: String = "***"

  /** Query parameter names whose value is credential material, compared case-insensitively.
    *
    * `sudo` is included because it names the account a request impersonates, which is as sensitive as the token that
    * authorises the impersonation.
    */
  val SensitiveQueryParameters: Set[String] = Set("token", "access_token", "private_token", "password", "sudo")

  /** Header names whose value is credential material, compared case-insensitively. */
  val SensitiveHeaders: Set[String] = Set("authorization", "proxy-authorization", "cookie", "set-cookie")

  /** Renders a request URI with every component percent-encoded and every credential masked.
    *
    * Path segments and query values are encoded per RFC 3986: only `A-Z`, `a-z`, `0-9`, `-`, `.`, `_` and `~` survive
    * literally, everything else becomes the uppercase percent-encoding of its UTF-8 bytes. A segment containing `/`
    * therefore cannot forge a path even if a validated identifier ever let one through.
    *
    * The value of a parameter named in [[SensitiveQueryParameters]] is replaced by [[Mask]] and never encoded, so the
    * result shows `?token=***` rather than an encoded secret.
    *
    * The base URI is not trusted to be clean. `com.worxbend.codeberg4s.BaseUri.from` rejects one carrying
    * `user:password@`, a query or a fragment, but this method takes a plain `String` and a test fake can pass anything
    * at all, so those three parts are removed here as well. Without that, a password in a hand-built base URI would be
    * copied into every rendered URI, and therefore into every error and every telemetry event.
    *
    * @param baseUri
    *   the API root, expected to be normalised without a trailing slash
    * @param path
    *   unencoded path segments, in order; an empty list renders just the base URI
    * @param query
    *   query parameters in order, keys may repeat
    */
  def uri(baseUri: String, path: List[String], query: List[(String, String)]): String =
    s"${safeBase(baseUri)}${renderPath(path)}${renderQuery(query)}"

  /** Strips the parts of a base URI that must never be rendered: anything from the first `?` or `#`, and the
    * `user:password@` prefix of the authority.
    */
  private def safeBase(baseUri: String): String =
    withoutUserInfo(withoutTail(baseUri))

  /** The value up to its first `?` or `#`, dropping a query or fragment along with everything after it. */
  private def withoutTail(value: String): String =
    val tail = value.indexWhere(startsTail)
    if tail < 0 then value else value.take(tail)

  private def startsTail(char: Char): Boolean =
    char match
      case '?' | '#' => true
      case _ => false

  /** The value with any `user:password@` removed from its authority.
    *
    * The authority runs from `://` to the next `/`, so an `@` in a path segment — `https://forge.example/api/v1/@me` —
    * is left alone. A value with no `://` is returned unchanged: there is no authority to trim.
    */
  private def withoutUserInfo(value: String): String =
    val marker = value.indexOf(AuthorityMarker)
    if marker < 0 then value
    else
      val start = marker + AuthorityMarker.length
      val end   = value.indexOf('/', start)
      val at    = value.lastIndexOf('@', (if end < 0 then value.length else end) - 1)
      if at < start then value else value.substring(0, start) + value.substring(at + 1)

  /** What separates a scheme from an authority; the authority is where user information can hide. */
  private val AuthorityMarker: String = "://"

  /** Masks the value of every credential-carrying header, keeping order and every other header untouched.
    *
    * Nothing in this library puts an `Authorization` header into a [[CodebergRequest]] — the transport adds it — but a
    * telemetry implementation that wants to show the headers it sent goes through here first.
    */
  def headers(entries: List[(String, String)]): List[(String, String)] =
    entries.map((name, value) => (name, if isSensitiveHeader(name) then Mask else value))

  private def isSensitiveHeader(name: String): Boolean =
    SensitiveHeaders.contains(name.toLowerCase(Locale.ROOT))

  private def isSensitiveParameter(name: String): Boolean =
    SensitiveQueryParameters.contains(name.toLowerCase(Locale.ROOT))

  private def renderPath(path: List[String]): String =
    if path.isEmpty then "" else path.map(percentEncode).mkString("/", "/", "")

  private def renderQuery(query: List[(String, String)]): String =
    if query.isEmpty then ""
    else query.map((name, value) => s"${percentEncode(name)}=${renderValue(name, value)}").mkString("?", "&", "")

  private def renderValue(name: String, value: String): String =
    if isSensitiveParameter(name) then Mask else percentEncode(value)

  /** The uppercase hex alphabet, indexed by nibble. A `String` rather than an `Array[Char]` so that the lookup table
    * cannot be mutated by anything holding a reference to it.
    */
  private val HexDigits: String = "0123456789ABCDEF"

  /** Percent-encodes `value` per RFC 3986 into one buffer.
    *
    * Every octet is appended to a single [[StringBuilder]] rather than turned into its own `String` first. The result
    * is character-for-character what a per-octet `map(...).mkString` produces; only the allocation count differs, and
    * this runs on every path segment and every query value of every attempt of every call.
    */
  private def percentEncode(value: String): String =
    val octets  = value.getBytes(StandardCharsets.UTF_8)
    val encoded = StringBuilder(octets.length)
    octets.foreach(byte => appendEncoded(encoded, byte))
    encoded.toString

  /** Appends one UTF-8 octet: literally when it is unreserved, otherwise as `%` and two uppercase hex digits. */
  private def appendEncoded(target: StringBuilder, byte: Byte): Unit =
    val octet = byte & 0xFF
    if isUnreserved(octet.toChar) then target.append(octet.toChar).discard
    else target.append('%').append(HexDigits(octet >> 4)).append(HexDigits(octet & 0x0F)).discard

  /** Whether `char` is RFC 3986 unreserved.
    *
    * The four punctuation marks are matched literally rather than looked for inside a `"-._~"` string, so deciding a
    * character costs a comparison instead of a scan. A `match` rather than `==` because the build's Scalafix
    * configuration bans universal equality, and rather than `.equals` because that would box the `Char`.
    */
  private def isUnreserved(char: Char): Boolean =
    (char >= 'a' && char <= 'z') ||
    (char >= 'A' && char <= 'Z') ||
    (char >= '0' && char <= '9') ||
    (char match
      case '-' | '.' | '_' | '~' => true
      case _ => false)
