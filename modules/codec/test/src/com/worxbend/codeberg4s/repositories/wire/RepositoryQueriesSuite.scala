package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.RepositorySearchMode
import com.worxbend.codeberg4s.repositories.RepositorySearchQuery
import com.worxbend.codeberg4s.repositories.RepositorySearchSort
import com.worxbend.codeberg4s.repositories.SortDirection

import munit.FunSuite

/** The query string `GET /repos/search` sends.
  *
  * Asserted as ordered lists of pairs rather than as maps, because
  * [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list: order is part of what is sent.
  */
final class RepositoryQueriesSuite extends FunSuite:

  test("an empty query sends the paging window and nothing else"):
    assertEquals(
      RepositoryQueries.search(RepositorySearchQuery.Empty, PageParams.First),
      List("page" -> "1", "limit" -> "30"),
    )

  test("the keyword is sent as q, ahead of the window"):
    assertEquals(
      RepositoryQueries.search(searching("forgejo"), PageParams.First),
      List("q" -> "forgejo", "page" -> "1", "limit" -> "30"),
    )

  test("the three switches are emitted only when true, under the spellings the spec declares"):
    assertEquals(
      filters(RepositorySearchQuery.Empty.asTopic.includingDescriptions.ownedBy(7L).ownedExclusively),
      List("topic" -> "true", "includeDesc" -> "true", "uid" -> "7", "exclusive" -> "true"),
    )

  test("the three-valued filters send a false too, because that means only the other kind"):
    assertEquals(
      filters(
        RepositorySearchQuery.Empty
          .withPrivate(false)
          .restrictedToPrivate(false)
          .restrictedToTemplates(true)
          .restrictedToArchived(false)),
      List("private" -> "false", "is_private" -> "false", "template" -> "true", "archived" -> "false"),
    )

  test("the numeric filters keep their spec spellings, camel case and all"):
    assertEquals(
      filters(RepositorySearchQuery.Empty.prioritisingOwner(3L).inTeam(11L).starredBy(42L)),
      List("priority_owner_id" -> "3", "team_id" -> "11", "starredBy" -> "42"),
    )

  test("mode, sort and order render their enum wire values"):
    assertEquals(
      filters(
        RepositorySearchQuery.Empty
          .onlyOf(RepositorySearchMode.Collaborative)
          .sortedBy(RepositorySearchSort.GitSize)
          .inOrder(SortDirection.Descending)),
      List("mode" -> "collaborative", "sort" -> "git_size", "order" -> "desc"),
    )

  test("parameters keep the order the spec declares them in, with the window last"):
    val everything =
      searching("forgejo").asTopic
        .includingDescriptions
        .ownedBy(7L)
        .prioritisingOwner(3L)
        .inTeam(11L)
        .starredBy(42L)
        .withPrivate(true)
        .restrictedToPrivate(true)
        .restrictedToTemplates(false)
        .restrictedToArchived(false)
        .onlyOf(RepositorySearchMode.Source)
        .ownedExclusively
        .sortedBy(RepositorySearchSort.Stars)
        .inOrder(SortDirection.Ascending)

    assertEquals(
      RepositoryQueries.search(everything, PageParams.First).map(_._1),
      List(
        "q",
        "topic",
        "includeDesc",
        "uid",
        "priority_owner_id",
        "team_id",
        "starredBy",
        "private",
        "is_private",
        "template",
        "archived",
        "mode",
        "exclusive",
        "sort",
        "order",
        "page",
        "limit",
      ),
    )

  /** The search parameters without the paging window, which every case above would otherwise repeat. */
  private def filters(query: RepositorySearchQuery): List[(String, String)] =
    RepositoryQueries.search(query, PageParams.First).filterNot((name, _) => name == "page" || name == "limit")

  private def searching(keyword: String): RepositorySearchQuery =
    orFail(RepositorySearchQuery.of(keyword))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
