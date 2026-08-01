package com.worxbend.codeberg4s.core

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
    * @param baseUri
    *   the API root, already normalised without a trailing slash
    * @param path
    *   unencoded path segments, in order; an empty list renders just the base URI
    * @param query
    *   query parameters in order, keys may repeat
    */
  def uri(baseUri: String, path: List[String], query: List[(String, String)]): String =
    s"$baseUri${renderPath(path)}${renderQuery(query)}"

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

  private def percentEncode(value: String): String =
    value.getBytes(StandardCharsets.UTF_8).map(encodeByte).mkString

  private def encodeByte(byte: Byte): String =
    val octet = byte & 0xFF
    if isUnreserved(octet.toChar) then octet.toChar.toString else f"%%$octet%02X"

  private def isUnreserved(char: Char): Boolean =
    (char >= 'a' && char <= 'z') ||
    (char >= 'A' && char <= 'Z') ||
    (char >= '0' && char <= '9') ||
    "-._~".contains(char)
