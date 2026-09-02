package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.CodebergError

import munit.FunSuite

/** [[RepositorySearchQuery]]'s smart constructor and its wither methods.
  *
  * The wire spellings are not asserted here — they live in `modules/codec` and are pinned by
  * `RepositoryQueriesSuite`. What this suite pins is what a caller can and cannot build.
  */
final class RepositorySearchQuerySuite extends FunSuite:

  test("Empty carries no keyword and no filter"):
    assertEquals(RepositorySearchQuery.Empty.text, None)
    assertEquals(RepositorySearchQuery.Empty.topicOnly, false)
    assertEquals(RepositorySearchQuery.Empty.includePrivate, None)

  test("of trims the keyword, because one is routinely pasted"):
    assertEquals(RepositorySearchQuery.of("  forgejo  ").map(_.text), Right(Some("forgejo")))

  test("a blank keyword is no keyword, since q= and an absent q ask the same question"):
    assertEquals(RepositorySearchQuery.of("   ").map(_.text), Right(None))

  test("a control character in the keyword is rejected on the text field"):
    RepositorySearchQuery.of("for\ngejo") match
      case Left(CodebergError.Validation(field, _)) => assertEquals(field, "text")
      case other                                    => fail(s"expected a validation failure, got $other")

  test("the switches turn on and stay on"):
    val query = RepositorySearchQuery.Empty.asTopic.includingDescriptions.ownedExclusively

    assertEquals((query.topicOnly, query.includeDescription, query.exclusive), (true, true, true))

  test("the three-valued filters can be set to false, which is a request of its own"):
    val query = RepositorySearchQuery.Empty.restrictedToArchived(false)

    assertEquals(query.onlyArchived, Some(false))

  test("the ids land on the fields the endpoint means them for"):
    val query = RepositorySearchQuery.Empty.ownedBy(1L).prioritisingOwner(2L).inTeam(3L).starredBy(4L)

    assertEquals((query.ownerId, query.priorityOwnerId, query.teamId, query.starredById),
                 (Some(1L), Some(2L), Some(3L), Some(4L)))

  test("ordering is two decisions, an attribute and a direction"):
    val query = RepositorySearchQuery.Empty.sortedBy(RepositorySearchSort.Stars).inOrder(SortDirection.Descending)

    assertEquals((query.sort, query.order), (Some(RepositorySearchSort.Stars), Some(SortDirection.Descending)))

  test("every sort value spells itself exactly as the spec enumerates it"):
    assertEquals(
      RepositorySearchSort.values.map(_.wireValue).toList,
      List("alpha", "created", "updated", "size", "git_size", "lfs_size", "id", "stars", "forks"),
    )

  test("every mode value spells itself as the spec's description names it"):
    assertEquals(
      RepositorySearchMode.values.map(_.wireValue).toList,
      List("fork", "source", "mirror", "collaborative"),
    )

  test("a direction is asc or desc and nothing else"):
    assertEquals(SortDirection.values.map(_.wireValue).toList, List("asc", "desc"))
