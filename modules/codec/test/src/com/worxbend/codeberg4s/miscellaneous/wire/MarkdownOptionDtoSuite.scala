package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.ValidationError
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

    assertEquals(body.obj.keySet.toSet, Set("Text", "Mode", "Wiki"))

  test("a standalone request renders as plain markdown, not as a wiki page"):
    val body = rendered(MarkdownRenderRequest.of("# Title"))

    assertEquals(body("Text").str, "# Title")
    assertEquals(body("Mode").str, "markdown")
    assertEquals(body("Wiki").bool, false)

  test("a context is sent only when there is one"):
    val request = MarkdownRenderRequest(
      text    = "see #12",
      mode    = MarkdownMode.Comment,
      context = Some(orFail(MarkdownContext.from("codeberg/Community"))),
      page    = MarkdownPageKind.Wiki,
    )

    val body = rendered(request)

    assertEquals(body("Context").str, "codeberg/Community")
    assertEquals(body("Mode").str, "comment")
    assertEquals(body("Wiki").bool, true)

  test("markdown that would break a JSON document is escaped, not passed through"):
    val body = rendered(MarkdownRenderRequest.of("a \"quote\" and a \\ and a\nnewline"))

    assertEquals(body("Text").str, "a \"quote\" and a \\ and a\nnewline")

  private def rendered(request: MarkdownRenderRequest): ujson.Value =
    ujson.read(MarkdownOptionDto.fromDomain(request).toJson)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
