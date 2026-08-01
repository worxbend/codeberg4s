package com.worxbend.codeberg4s.core

import munit.FunSuite

final class LinkHeaderSuite extends FunSuite:

  private val forgejoHeader: String =
    "<https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=2>; rel=\"next\"," +
      "<https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=795>; rel=\"last\""

  test("a live Forgejo header yields the next and last targets"):
    assertEquals(
      LinkHeader.parse(forgejoHeader),
      Map(
        "next" -> "https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=2",
        "last" -> "https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=795",
      ),
    )

  test("elements separated by a comma with no following space are still separate"):
    assertEquals(LinkHeader.parse("<a?page=2>;rel=\"next\",<a?page=9>;rel=\"last\"").size, 2)

  test("an unquoted rel value is read exactly like a quoted one"):
    assertEquals(LinkHeader.parse("<a?page=2>; rel=next"), Map("next" -> "a?page=2"))

  test("a relation type is matched case-insensitively"):
    assertEquals(LinkHeader.parse("<a?page=2>; rel=\"NEXT\""), Map("next" -> "a?page=2"))

  test("one element declaring several relation types contributes one entry each"):
    assertEquals(LinkHeader.parse("<a?page=1>; rel=\"first prev\""), Map("first" -> "a?page=1", "prev" -> "a?page=1"))

  test("a last page carries no next relation"):
    val links = LinkHeader.parse("<a?page=1>; rel=\"first\",<a?page=794>; rel=\"prev\"")

    assertEquals(links.get("next"), None)
    assertEquals(links.get("prev"), Some("a?page=794"))

  test("a comma inside the target does not split the element"):
    assertEquals(LinkHeader.parse("<a?q=x,y&page=2>; rel=\"next\""), Map("next" -> "a?q=x,y&page=2"))

  test("the first occurrence of a repeated relation type wins"):
    assertEquals(LinkHeader.parse("<a?page=2>; rel=\"next\",<b?page=3>; rel=\"next\""), Map("next" -> "a?page=2"))

  test("extra link parameters other than rel are ignored"):
    assertEquals(LinkHeader.parse("<a?page=2>; type=\"text/html\"; rel=\"next\""), Map("next" -> "a?page=2"))

  test("an element with no rel parameter contributes nothing"):
    assertEquals(LinkHeader.parse("<a?page=2>"), Map.empty[String, String])

  test("an element with no target contributes nothing"):
    assertEquals(LinkHeader.parse("rel=\"next\""), Map.empty[String, String])

  test("a wholly malformed header yields an empty map rather than an error"):
    assertEquals(LinkHeader.parse("<<<;;;>>>,,,"), Map.empty[String, String])

  test("an empty header yields an empty map"):
    assertEquals(LinkHeader.parse(""), Map.empty[String, String])

  test("an unreadable element is skipped while its readable neighbour survives"):
    assertEquals(LinkHeader.parse("garbage,<a?page=2>; rel=\"next\""), Map("next" -> "a?page=2"))

  test("surrounding whitespace around the target and the rel value is trimmed"):
    assertEquals(LinkHeader.parse("  < a?page=2 > ;  rel =  \"next\"  "), Map("next" -> "a?page=2"))

  test("a query parameter is read out of a target"):
    assertEquals(LinkHeader.queryParameter("https://x/api?limit=2&page=795", "page"), Some("795"))

  test("a query parameter name is matched case-insensitively"):
    assertEquals(LinkHeader.queryParameter("https://x/api?PAGE=3", "page"), Some("3"))

  test("a target with no query string has no parameters"):
    assertEquals(LinkHeader.queryParameter("https://x/api", "page"), None)

  test("a parameter the target does not carry is absent"):
    assertEquals(LinkHeader.queryParameter("https://x/api?limit=2", "page"), None)

  test("a fragment is not mistaken for part of the last parameter value"):
    assertEquals(LinkHeader.queryParameter("https://x/api?page=4#top", "page"), Some("4"))
