package com.worxbend.codeberg4s.users.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.UserSearchQuery
import com.worxbend.codeberg4s.users.UserSearchSort

import munit.FunSuite

/** The query string `GET /users/search` sends.
  *
  * Asserted as an ordered list of pairs rather than as a map, because
  * [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list: order is part of what is sent.
  */
final class UserQueriesSuite extends FunSuite:

  test("an empty query sends the paging window and nothing else"):
    assertEquals(
      UserQueries.search(UserSearchQuery.Empty, PageParams.First),
      List("page" -> "1", "limit" -> "30"),
    )

  test("the keyword is sent as q, ahead of the window"):
    assertEquals(
      UserQueries.search(orFail(UserSearchQuery.of("earl")), PageParams.First),
      List("q" -> "earl", "page" -> "1", "limit" -> "30"),
    )

  test("all three filters keep the order the spec declares them in, with the window last"):
    val query = orFail(UserSearchQuery.of("earl")).forUserId(12L).sortedBy(UserSearchSort.ReverseAlphabetically)

    assertEquals(
      UserQueries.search(query, PageParams.First),
      List(
        "q"     -> "earl",
        "uid"   -> "12",
        "sort"  -> "reversealphabetically",
        "page"  -> "1",
        "limit" -> "30",
      ),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
