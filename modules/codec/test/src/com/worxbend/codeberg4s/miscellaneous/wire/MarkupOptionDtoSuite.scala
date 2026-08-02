package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.miscellaneous.MarkdownContext
import com.worxbend.codeberg4s.miscellaneous.MarkupMode
import com.worxbend.codeberg4s.miscellaneous.MarkupRenderRequest

import munit.FunSuite

/** The second body this module writes rather than reads. Asserted as parsed JSON rather than as a string, because key
  * order is not part of the contract — the capitalised '''names''' are.
  */
final class MarkupOptionDtoSuite extends FunSuite:

  test("only the three always-sent keys appear when the caller set nothing else"):
    val body = rendered(MarkupRenderRequest.of("# Title", MarkupMode.Markdown))

    assertEquals(body.keys.toSet, Set("Text", "Mode", "Wiki"))

  test("a file request carries the file name the 'file' mode dispatches on"):
    val body = rendered(MarkupRenderRequest.ofFile("* Title", "README.org"))

    assertEquals(body.field("Text").flatMap(_.strOpt), Some("* Title"))
    assertEquals(body.field("Mode").flatMap(_.strOpt), Some("file"))
    assertEquals(body.field("FilePath").flatMap(_.strOpt), Some("README.org"))
    assertEquals(body.field("Wiki").flatMap(_.boolOpt), Some(false))

  test("every mode renders as the lowercase token the spec documents"):
    assertEquals(
      rendered(MarkupRenderRequest.of("x", MarkupMode.Comment)).field("Mode").flatMap(_.strOpt),
      Some("comment"),
    )
    assertEquals(rendered(MarkupRenderRequest.of("x", MarkupMode.Gfm)).field("Mode").flatMap(_.strOpt), Some("gfm"))
    assertEquals(
      rendered(MarkupRenderRequest.of("x", MarkupMode.Markdown)).field("Mode").flatMap(_.strOpt),
      Some("markdown"),
    )
    assertEquals(rendered(MarkupRenderRequest.of("x", MarkupMode.File)).field("Mode").flatMap(_.strOpt), Some("file"))

  test("the context, the branch and the wiki flag all reach the body when the caller sets them"):
    val request = MarkupRenderRequest
      .of("see #12", MarkupMode.Comment)
      .inContext(orFail(MarkdownContext.from("codeberg/Community")))
      .onBranch("main")
      .asWikiPage

    val body = rendered(request)

    assertEquals(body.field("Context").flatMap(_.strOpt), Some("codeberg/Community"))
    assertEquals(body.field("BranchPath").flatMap(_.strOpt), Some("main"))
    assertEquals(body.field("Wiki").flatMap(_.boolOpt), Some(true))

  test("markup that would break a JSON document is escaped, not passed through"):
    val body = rendered(MarkupRenderRequest.of("a \"quote\" and a \\ and a\nnewline", MarkupMode.Gfm))

    assertEquals(body.field("Text").flatMap(_.strOpt), Some("a \"quote\" and a \\ and a\nnewline"))

  test("a file path carrying a quote is escaped too, which is why the domain does not validate one"):
    val body = rendered(MarkupRenderRequest.ofFile("x", "weird\"name\".org"))

    assertEquals(body.field("FilePath").flatMap(_.strOpt), Some("weird\"name\".org"))

  private def rendered(request: MarkupRenderRequest): JsonValue =
    Json.parse(MarkupOptionDto.fromDomain(request).toJson).getOrElse(fail("the rendered body did not parse"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
