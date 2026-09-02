package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.CodebergError

import munit.FunSuite

/** [[UserSearchQuery]]'s smart constructor and its wither methods.
  *
  * The wire spellings are pinned in `modules/codec` by `UserQueriesSuite`; what this suite pins is what a caller can
  * build.
  */
final class UserSearchQuerySuite extends FunSuite:

  test("Empty carries neither a keyword, an id nor an ordering"):
    assertEquals(UserSearchQuery.Empty, UserSearchQuery(text = None, userId = None, sort = None))

  test("of trims the keyword"):
    assertEquals(UserSearchQuery.of(" earl ").map(_.text), Right(Some("earl")))

  test("a blank keyword is no keyword"):
    assertEquals(UserSearchQuery.of("  ").map(_.text), Right(None))

  test("a control character in the keyword is rejected on the text field"):
    UserSearchQuery.of("ea\trl") match
      case Left(CodebergError.Validation(field, _)) => assertEquals(field, "text")
      case other                                    => fail(s"expected a validation failure, got $other")

  test("an id and an ordering are added to the keyword rather than replacing it"):
    val query = UserSearchQuery.Empty.forUserId(12L).sortedBy(UserSearchSort.RecentUpdate)

    assertEquals((query.userId, query.sort), (Some(12L), Some(UserSearchSort.RecentUpdate)))

  test("every sort value spells itself exactly as the spec enumerates it"):
    assertEquals(
      UserSearchSort.values.map(_.wireValue).toList,
      List("oldest", "newest", "alphabetically", "reversealphabetically", "recentupdate", "leastupdate"),
    )
