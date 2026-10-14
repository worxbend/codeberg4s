package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.auth.{ApiToken, Auth, Password}
import com.worxbend.codeberg4s.{TransportCause, ValidationError}

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64

final class DiagnosticRedactionSuite extends FunSuite:

  private def valid[A](value: Either[ValidationError, A]): A = value match
    case Right(result) => result
    case Left(error)   => fail(s"invalid synthetic fixture: $error")

  private def token(value: String): DiagnosticRedaction = DiagnosticRedaction(Auth.Token(valid(ApiToken.from(value))))

  private def basic(username: String, password: String): DiagnosticRedaction =
    DiagnosticRedaction(valid(Auth.basic(username, valid(Password.from(password)))))

  test("overlapping occurrences are masked as one span rather than leaving a credential suffix"):
    assertEquals(token("aba").text("before ababa after"), s"before ${Redaction.Mask} after")

  test("repeated adjacent occurrences and separated occurrences are all masked"):
    assertEquals(
      token("diagnostic-only-token").text("diagnostic-only-tokendiagnostic-only-token / diagnostic-only-token"),
      s"${Redaction.Mask}${Redaction.Mask} / ${Redaction.Mask}"
    )

  test("a raw password overlapping the beginning of its Basic wire value does not prevent the longer match"):
    val password = "dT"
    val wire     = Base64.getEncoder.encodeToString(s"u:$password".getBytes(StandardCharsets.UTF_8))
    assert(wire.startsWith(password), "fixture must exercise a shorter match at the same position")
    assertEquals(basic("u", password).text(s"Basic $wire / $password"), s"Basic ${Redaction.Mask} / ${Redaction.Mask}")

  test("password whitespace and Unicode match the exact UTF-8 Basic wire encoding"):
    val password = " pässwörd-雪 "
    val wire     = Base64.getEncoder.encodeToString(s"üser:$password".getBytes(StandardCharsets.UTF_8))
    assertEquals(
      basic(" üser ", password).text(s"raw:$password wire:$wire"),
      s"raw:${Redaction.Mask} wire:${Redaction.Mask}"
    )

  test("header edge representations do not become generic text redaction false positives"):
    val password  = " diagnostic-only-header-password "
    val redaction = basic("u", password)
    Vector(password.trim, password.stripLeading(), password.stripTrailing()).foreach: ordinary =>
      assertEquals(redaction.text(s"ordinary:$ordinary:end"), s"ordinary:$ordinary:end")
    assertEquals(redaction.text(s"raw:$password:end"), s"raw:${Redaction.Mask}:end")

  test("header diagnostics alone scrub raw and adapter-normalized password representations before trimming"):
    val password  = "  diagnostic-only-header-password  "
    val redaction = basic("u", password)
    Vector(password, password.stripLeading(), password.stripTrailing(), password.trim).foreach: representation =>
      assertEquals(redaction.prefixHeader(representation, 200), Some(Redaction.Mask))
      assertEquals(
        redaction.prefixHeader(s"ordinary:$representation:end", 200),
        Some(s"ordinary:${Redaction.Mask}:end")
      )
    assertEquals(redaction.prefixHeader(" \t ordinary-id \t ", 200), Some("ordinary-id"))
    assertEquals(redaction.prefixHeader(" \t ", 200), None)
    assertEquals(redaction.prefixHeader(password, 0), None)

  test("header diagnostics match the complete value before clipping credential-edge representations"):
    val password = " diagnostic-only-header-password "
    val prefix   = "x".repeat(195)
    assertEquals(
      basic("u", password).prefixHeader(prefix + password.stripTrailing() + "  tail", 200),
      Some(prefix + Redaction.Mask + " t")
    )

  test("credential metacharacters are literal, not a regular expression"):
    assertEquals(token("a.*[b]$").text("aZZb / a.*[b]$ / a.*[b]$"), s"aZZb / ${Redaction.Mask} / ${Redaction.Mask}")

  test("clipping happens after full credential matching, including nonpositive limits"):
    val redaction = token("diagnostic-only-token")
    val prefix    = "x".repeat(195)
    assertEquals(redaction.prefix(prefix + "diagnostic-only-token tail", 200), prefix + Redaction.Mask + " t")
    assertEquals(redaction.prefix("diagnostic-only-token", 0), "")
    assertEquals(redaction.prefix("diagnostic-only-token", -1), "")

  test("anonymous, empty, and nonmatching text remain unchanged"):
    assertEquals(DiagnosticRedaction(Auth.Anonymous).text("ordinary reason"), "ordinary reason")
    assertEquals(token("diagnostic-only-token").text(""), "")
    assertEquals(token("diagnostic-only-token").text("ordinary reason"), "ordinary reason")

  test("a credential matching the mask itself is withheld instead of reintroduced"):
    assertEquals(basic("u", "*").text("echo *"), "")

  test("a mask cannot recreate a configured credential at its boundary with ordinary text"):
    assertEquals(token("x***").text("xx***"), "")

  test("all transport categories survive while details are scrubbed and bounded"):
    val redaction                                      = token("diagnostic-only-token")
    val detail                                         = "diagnostic-only-token " + "x".repeat(300)
    val safe                                           = Redaction.Mask + " " + "x".repeat(196)
    val constructors: Vector[String => TransportCause] = Vector(
      TransportCause.ConnectionFailed.apply,
      TransportCause.Timeout.apply,
      TransportCause.Tls.apply,
      TransportCause.Dns.apply,
      TransportCause.Interrupted.apply,
      TransportCause.ResponseTooLarge.apply,
      TransportCause.Unknown.apply,
    )
    constructors.foreach: construct =>
      assertEquals(redaction.cause(construct(detail)), construct(safe))
