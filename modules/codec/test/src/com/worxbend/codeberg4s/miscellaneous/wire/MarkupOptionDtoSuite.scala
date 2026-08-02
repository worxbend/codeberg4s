package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.ValidationError
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

    assertEquals(body.obj.keySet.toSet, Set("Text", "Mode", "Wiki"))

  test("a file request carries the file name the 'file' mode dispatches on"):
    val body = rendered(MarkupRenderRequest.ofFile("* Title", "README.org"))

    assertEquals(body("Text").str, "* Title")
    assertEquals(body("Mode").str, "file")
    assertEquals(body("FilePath").str, "README.org")
    assertEquals(body("Wiki").bool, false)

  test("every mode renders as the lowercase token the spec documents"):
    assertEquals(rendered(MarkupRenderRequest.of("x", MarkupMode.Comment))("Mode").str, "comment")
    assertEquals(rendered(MarkupRenderRequest.of("x", MarkupMode.Gfm))("Mode").str, "gfm")
    assertEquals(rendered(MarkupRenderRequest.of("x", MarkupMode.Markdown))("Mode").str, "markdown")
    assertEquals(rendered(MarkupRenderRequest.of("x", MarkupMode.File))("Mode").str, "file")

  test("the context, the branch and the wiki flag all reach the body when the caller sets them"):
    val request = MarkupRenderRequest
      .of("see #12", MarkupMode.Comment)
      .inContext(orFail(MarkdownContext.from("codeberg/Community")))
      .onBranch("main")
      .asWikiPage

    val body = rendered(request)

    assertEquals(body("Context").str, "codeberg/Community")
    assertEquals(body("BranchPath").str, "main")
    assertEquals(body("Wiki").bool, true)

  test("markup that would break a JSON document is escaped, not passed through"):
    val body = rendered(MarkupRenderRequest.of("a \"quote\" and a \\ and a\nnewline", MarkupMode.Gfm))

    assertEquals(body("Text").str, "a \"quote\" and a \\ and a\nnewline")

  test("a file path carrying a quote is escaped too, which is why the domain does not validate one"):
    val body = rendered(MarkupRenderRequest.ofFile("x", "weird\"name\".org"))

    assertEquals(body("FilePath").str, "weird\"name\".org")

  private def rendered(request: MarkupRenderRequest): ujson.Value =
    ujson.read(MarkupOptionDto.fromDomain(request).toJson)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
