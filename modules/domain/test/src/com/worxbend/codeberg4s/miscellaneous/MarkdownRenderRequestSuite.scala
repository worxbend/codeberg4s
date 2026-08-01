package com.worxbend.codeberg4s.miscellaneous

import munit.FunSuite

/** The rendering options, and the wire tokens the codec spells them with. Forgejo matches `Mode` against literal
  * lowercase strings and falls through to plain markdown on anything it does not know, so a mistyped token is a silent
  * change of behaviour rather than an error — which is why the tokens are asserted here rather than trusted.
  */
final class MarkdownRenderRequestSuite extends FunSuite:

  test("every mode has the lowercase token Forgejo matches on"):
    assertEquals(MarkdownMode.values.toVector.map(_.wireName), Vector("comment", "gfm", "markdown"))

  test("only a wiki page is a wiki page"):
    assertEquals(MarkdownPageKind.Regular.isWiki, false)
    assertEquals(MarkdownPageKind.Wiki.isWiki, true)

  test("a standalone request resolves nothing against a repository"):
    val request = MarkdownRenderRequest.of("# Title")

    assertEquals(request.text, "# Title")
    assertEquals(request.mode, MarkdownMode.Markdown)
    assertEquals(request.context, None)
    assertEquals(request.page, MarkdownPageKind.Regular)
