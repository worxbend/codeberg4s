package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.miscellaneous.MarkdownContext
import com.worxbend.codeberg4s.miscellaneous.MarkdownMode
import com.worxbend.codeberg4s.miscellaneous.MarkdownPageKind
import com.worxbend.codeberg4s.miscellaneous.MarkdownRenderRequest

import munit.FunSuite

/** The one body this module writes rather than reads. Asserted as parsed JSON rather than as a string, because key
  * order is not part of the contract — the capitalised '''names''' are.
  */
final class MarkdownOptionDtoSuite extends FunSuite:

  test("the field names are Forgejo's capitalised Go field names"):
    val body = rendered(MarkdownRenderRequest.of("# Title"))

    assertEquals(body.keys.toSet, Set("Text", "Mode", "Wiki"))

  test("a standalone request renders as plain markdown, not as a wiki page"):
    val body = rendered(MarkdownRenderRequest.of("# Title"))

    assertEquals(body.field("Text").flatMap(_.strOpt), Some("# Title"))
    assertEquals(body.field("Mode").flatMap(_.strOpt), Some("markdown"))
    assertEquals(body.field("Wiki").flatMap(_.boolOpt), Some(false))

  test("a context is sent only when there is one"):
    val request = MarkdownRenderRequest(
      text    = "see #12",
      mode    = MarkdownMode.Comment,
      context = Some(orFail(MarkdownContext.from("codeberg/Community"))),
      page    = MarkdownPageKind.Wiki,
    )

    val body = rendered(request)

    assertEquals(body.field("Context").flatMap(_.strOpt), Some("codeberg/Community"))
    assertEquals(body.field("Mode").flatMap(_.strOpt), Some("comment"))
    assertEquals(body.field("Wiki").flatMap(_.boolOpt), Some(true))

  test("markdown that would break a JSON document is escaped, not passed through"):
    val body = rendered(MarkdownRenderRequest.of("a \"quote\" and a \\ and a\nnewline"))

    assertEquals(body.field("Text").flatMap(_.strOpt), Some("a \"quote\" and a \\ and a\nnewline"))

  private def rendered(request: MarkdownRenderRequest): JsonValue =
    Json.parse(MarkdownOptionDto.fromDomain(request).toJson).getOrElse(fail("the rendered body did not parse"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
