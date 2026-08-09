package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.paging.PageNumber

import munit.FunSuite

final class CodebergResponsePagingSuite extends FunSuite:

  private val forgejoLinks: String =
    "<https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=2>; rel=\"next\"," +
      "<https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=795>; rel=\"last\""

  private def responseWith(headers: (String, List[String])*): CodebergResponse =
    CodebergResponse(200, headers.toMap, ResponseBody.utf8("[]"))

  private def page(value: Int): PageNumber =
    PageNumber.from(value).getOrElse(PageNumber.First)

  test("the links of a live Forgejo listing are exposed by relation type"):
    val response = responseWith("link" -> List(forgejoLinks))

    assertEquals(response.links.keySet, Set("next", "last"))

  test("the next page number is read out of the next relation"):
    assertEquals(responseWith("link" -> List(forgejoLinks)).nextPage, Some(page(2)))

  test("the last page number is read out of the last relation"):
    assertEquals(responseWith("link" -> List(forgejoLinks)).lastPage, Some(page(795)))

  test("a response with no link header offers no following page"):
    assertEquals(responseWith().nextPage, None)

  test("the final page of a collection offers no following page"):
    val response = responseWith("link" -> List("<https://x/api?page=794>; rel=\"prev\""))

    assertEquals(response.nextPage, None)
    assertEquals(response.prevPage, Some(page(794)))

  test("a previous relation spelled the long way is accepted"):
    val response = responseWith("link" -> List("<https://x/api?page=3>; rel=\"previous\""))

    assertEquals(response.prevPage, Some(page(3)))

  test("several link headers are read as one, as if joined with commas"):
    val response = responseWith(
      "link" -> List("<https://x/api?page=2>; rel=\"next\"", "<https://x/api?page=9>; rel=\"last\"")
    )

    assertEquals(response.nextPage, Some(page(2)))
    assertEquals(response.lastPage, Some(page(9)))

  test("a link whose target carries no page parameter yields no page number"):
    assertEquals(responseWith("link" -> List("<https://x/api>; rel=\"next\"")).nextPage, None)

  test("a page parameter that is not a number yields no page number"):
    assertEquals(responseWith("link" -> List("<https://x/api?page=next>; rel=\"next\"")).nextPage, None)

  test("a page parameter below one is rejected rather than clamped"):
    assertEquals(responseWith("link" -> List("<https://x/api?page=0>; rel=\"next\"")).nextPage, None)

  test("an unreadable link header is not an error, it is simply no links"):
    assertEquals(responseWith("link" -> List("nonsense")).links, Map.empty[String, String])
