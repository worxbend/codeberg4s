package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[MarkupRenderRequest]] and [[MarkupMode]]: the builders, and the one combination the instance rejects. */
final class MarkupRenderRequestSuite extends FunSuite:

  test("a standalone request carries nothing but the text and the mode"):
    val request = MarkupRenderRequest.of("# Title", MarkupMode.Gfm)

    assertEquals(request.text, "# Title")
    assertEquals(request.mode, MarkupMode.Gfm)
    assertEquals(request.context, None)
    assertEquals(request.filePath, None)
    assertEquals(request.branchPath, None)
    assertEquals(request.page, MarkdownPageKind.Regular)

  test("ofFile is the one combination that is easy to get wrong, written out"):
    val request = MarkupRenderRequest.ofFile("* Title", "README.org")

    assertEquals(request.mode, MarkupMode.File)
    assertEquals(request.filePath, Some("README.org"))

  test("the builders each change one thing and leave the rest alone"):
    val request = MarkupRenderRequest
      .of("x", MarkupMode.Comment)
      .asFile("guide.adoc")
      .onBranch("main")
      .asWikiPage

    assertEquals(request.mode, MarkupMode.Comment, "asFile must not change the mode")
    assertEquals(request.filePath, Some("guide.adoc"))
    assertEquals(request.branchPath, Some("main"))
    assertEquals(request.page, MarkdownPageKind.Wiki)

  test("a repository context is what makes a relative reference resolvable, and it sets nothing else"):
    val context = orFail(MarkdownContext.from("worxbend/codeberg4s"))
    val request = MarkupRenderRequest.of("see #1", MarkupMode.Gfm).inContext(context)

    assertEquals(request.context.map(_.value), Some("worxbend/codeberg4s"))
    assertEquals(request.filePath, None)
    assertEquals(request.branchPath, None)
    assertEquals(request.page, MarkdownPageKind.Regular)
    assertEquals(request.mode, MarkupMode.Gfm)

  test("a later context replaces the earlier one rather than being ignored"):
    val first   = orFail(MarkdownContext.from("worxbend/codeberg4s"))
    val second  = orFail(MarkdownContext.from("forgejo/forgejo"))
    val request = MarkupRenderRequest.of("x", MarkupMode.Gfm).inContext(first).inContext(second)

    assertEquals(request.context.map(_.value), Some("forgejo/forgejo"))

  test("every mode has the lowercase token the spec documents, and they are all distinct"):
    val tokens = MarkupMode.values.toVector.map(_.wireName)

    assertEquals(tokens, Vector("comment", "gfm", "markdown", "file"))
    assertEquals(tokens.distinct.size, tokens.size)

  test("only the file mode needs a file name to dispatch on"):
    assertEquals(MarkupMode.values.toVector.filter(_.needsFilePath), Vector(MarkupMode.File))

  test("the four markup modes are a strict superset of the three markdown ones"):
    val markup   = MarkupMode.values.toVector.map(_.wireName).toSet
    val markdown = MarkdownMode.values.toVector.map(_.wireName).toSet

    assert(markdown.subsetOf(markup), "a markdown mode has no markup counterpart")
    assertEquals(markup.diff(markdown), Set("file"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
