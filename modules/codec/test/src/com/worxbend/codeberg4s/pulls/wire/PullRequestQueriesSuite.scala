package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.MilestoneId
import com.worxbend.codeberg4s.issues.StateFilter
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.pulls.PullRequestHead
import com.worxbend.codeberg4s.pulls.PullRequestQuery
import com.worxbend.codeberg4s.pulls.PullRequestSort
import com.worxbend.codeberg4s.repositories.BranchName

import munit.FunSuite

/** [[PullRequestQueries]] — the query string of `GET /repos/{owner}/{repo}/pulls`. */
final class PullRequestQueriesSuite extends FunSuite:

  test("an empty query sends nothing, so Forgejo applies its own default of open only"):
    assertEquals(PullRequestQueries.pulls(PullRequestQuery.Empty), Nil)

  test("paging always sends both parameters, because limit alone is silently ignored"):
    assertEquals(
      PullRequestQueries.paging(PageParams(orFail(PageNumber.from(3)), orFail(PageSize.from(25)))),
      List("page" -> "3", "limit" -> "25"),
    )

  test("only the filters the caller set are emitted, in the order the spec declares them"):
    val query = PullRequestQuery.Empty
      .withState(StateFilter.All)
      .sortedBy(PullRequestSort.RecentUpdate)
      .authoredBy("trim21")

    assertEquals(
      PullRequestQueries.pulls(query),
      List("state" -> "all", "sort" -> "recentupdate", "poster" -> "trim21"),
    )

  /** The issue listing joins its `labels` with commas; this endpoint declares `collectionFormat: multi`. */
  test("labels are repeated rather than comma-joined, which is why the query is a list and not a map"):
    val query = PullRequestQuery.Empty.withLabels(
      Vector(orFail(LabelId.from(201023L)), orFail(LabelId.from(201030L)))
    )

    assertEquals(PullRequestQueries.pulls(query), List("labels" -> "201023", "labels" -> "201030"))

  test("labels are appended after the scalar filters, which is where the renderer puts them"):
    val query = PullRequestQuery.Empty
      .withState(StateFilter.All)
      .withLabels(Vector(orFail(LabelId.from(201023L))))

    assertEquals(PullRequestQueries.pulls(query), List("state" -> "all", "labels" -> "201023"))

  test("an empty label vector removes the filter rather than sending an empty parameter"):
    assertEquals(PullRequestQueries.pulls(PullRequestQuery.Empty.withLabels(Vector.empty)), Nil)

  test("a milestone is sent as an id here, unlike the issue listing which takes titles"):
    assertEquals(
      PullRequestQueries.pulls(PullRequestQuery.Empty.inMilestone(orFail(MilestoneId.from(137464L)))),
      List("milestone" -> "137464"),
    )

  test("base and head are sent in the spellings their own types produce"):
    val fork  = orFail(Owner.from("trim21"))
    val topic = orFail(BranchName.from("fix-pep691"))
    val query = PullRequestQuery.Empty
      .withBase(orFail(BranchName.from("forgejo")))
      .withHead(PullRequestHead.crossRepository(fork, topic))

    assertEquals(PullRequestQueries.pulls(query), List("base" -> "forgejo", "head" -> "trim21:fix-pep691"))

  test("every sort ordering has its own wire spelling, run-together ones included"):
    assertEquals(
      PullRequestSort.values.toVector.map(_.wireValue),
      Vector("oldest", "recentupdate", "recentclose", "leastupdate", "mostcomment", "leastcomment", "priority"),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
