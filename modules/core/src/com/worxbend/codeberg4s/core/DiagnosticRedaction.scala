package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.syntax.discard

import scala.annotation.tailrec

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Scrubs configured credentials from diagnostics, never from response data handed to a decoder. */
private[codeberg4s] final class DiagnosticRedaction private (secrets: Vector[String], headerSecrets: Vector[String]):

  /** Masks the union of all matching spans, including overlapping and repeated occurrences. */
  def text(value: String): String = scrub(value, secrets)

  /** Header adapters can remove credential-edge whitespace before core sees a value. Keep those representations
    * confined to header diagnostics, and scrub the complete value before normalization or clipping.
    */
  private[core] def prefixHeader(value: String, maxChars: Int): Option[String] =
    Option(scrub(value, headerSecrets).trim.take(math.max(0, maxChars))).filter(_.nonEmpty)

  private def scrub(value: String, matches: Vector[String]): String =
    if matches.isEmpty || value.isEmpty then value
    else
      val result                                                = StringBuilder(value.length)
      @tailrec def append(index: Int, withheldUntil: Int): Unit =
        if index < value.length then
          val matchedUntil = matches.foldLeft(index): (end, secret) =>
            if value.startsWith(secret, index) then math.max(end, index + secret.length) else end
          if matchedUntil > index && index >= withheldUntil then result.append(Redaction.Mask).discard
          val nextBoundary = math.max(withheldUntil, matchedUntil)
          if index >= nextBoundary then result.append(value.charAt(index)).discard
          append(index + 1, nextBoundary)
      append(0, 0)
      val sanitized                                             = result.toString
      // A short credential can coincide with the mask, or be recreated where a mask meets ordinary text.
      if matches.exists(secret => sanitized.contains(secret)) then "" else sanitized

  /** Scrubs the complete value before clipping, so a boundary cannot expose a credential prefix. */
  def prefix(value: String, maxChars: Int): String = text(value).take(math.max(0, maxChars))

  private[core] def excerpt(body: ResponseBody, maxChars: Int): String =
    if secrets.isEmpty then body.excerpt(maxChars) else prefix(body.text, maxChars)

  /** Keeps the transport category while scrubbing and bounding its free-form detail. */
  def cause(value: TransportCause): TransportCause = mapDetail(value)(detail => prefix(detail, 200))

  private[core] def withheldCause(value: TransportCause): TransportCause = mapDetail(value)(_ => text(Redaction.Mask))

  private def mapDetail(value: TransportCause)(sanitize: String => String): TransportCause = value match
    case TransportCause.ConnectionFailed(detail) => TransportCause.ConnectionFailed(sanitize(detail))
    case TransportCause.Timeout(detail)          => TransportCause.Timeout(sanitize(detail))
    case TransportCause.Tls(detail)              => TransportCause.Tls(sanitize(detail))
    case TransportCause.Dns(detail)              => TransportCause.Dns(sanitize(detail))
    case TransportCause.Interrupted(detail)      => TransportCause.Interrupted(sanitize(detail))
    case TransportCause.ResponseTooLarge(detail) => TransportCause.ResponseTooLarge(sanitize(detail))
    case TransportCause.Unknown(detail)          => TransportCause.Unknown(sanitize(detail))

private[codeberg4s] object DiagnosticRedaction:

  def apply(auth: Auth): DiagnosticRedaction =
    val secrets     = auth match
      case Auth.Anonymous                 => Vector.empty
      case Auth.Token(token)              => Vector(token.reveal)
      case Auth.Basic(username, password) =>
        val raw  = password.reveal
        val wire = Base64.getEncoder.encodeToString(s"$username:$raw".getBytes(StandardCharsets.UTF_8))
        Vector(raw, wire)
    val headerEdges = auth match
      case Auth.Basic(_, password) =>
        val raw = password.reveal
        Vector(raw.stripLeading(), raw.stripTrailing(), raw.trim)
      case _                       => Vector.empty
    new DiagnosticRedaction(
      secrets.filter(_.nonEmpty).distinct,
      (secrets ++ headerEdges).filter(_.nonEmpty).distinct,
    )
