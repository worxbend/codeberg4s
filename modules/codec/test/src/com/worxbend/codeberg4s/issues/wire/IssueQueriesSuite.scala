package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.issues.LabelName
import com.worxbend.codeberg4s.issues.MilestoneTitle
import com.worxbend.codeberg4s.issues.StateFilter

import munit.FunSuite

import java.time.Instant

/** [[IssueQueries]]: what actually reaches the query string, and what deliberately does not. */
final class IssueQueriesSuite extends FunSuite:

  private val Bug: LabelName = orFail(LabelName.from("bug"))

  private val Upstream: LabelName = orFail(LabelName.from("upstream"))

  private val Release: MilestoneTitle = orFail(MilestoneTitle.from("Forgejo v1.18.0-0"))

  test("an empty query emits no parameters at all, so Forgejo's own default applies"):
    assertEquals(IssueQueries.issues(IssueQuery.Empty), Nil)

  test("only the filters the caller set are emitted"):
    assertEquals(IssueQueries.issues(IssueQuery.Empty.withState(StateFilter.All)), List("state" -> "all"))

  test("label names are joined with commas, which is why a comma cannot be in one"):
    assertEquals(
      IssueQueries.issues(IssueQuery.Empty.withLabels(Vector(Bug, Upstream))),
      List("labels" -> "bug,upstream"),
    )

  test("an empty label vector emits nothing rather than an empty parameter"):
    assertEquals(IssueQueries.issues(IssueQuery.Empty.withLabels(Vector.empty)), Nil)

  test("milestone titles are joined the same way"):
    assertEquals(
      IssueQueries.issues(IssueQuery.Empty.withMilestones(Vector(Release))),
      List("milestones" -> "Forgejo v1.18.0-0"),
    )

  test("timestamps are rendered as RFC-3339 with a Z offset, the form Go parses"):
    assertEquals(
      IssueQueries.issues(IssueQuery.Empty.updatedSince(Instant.parse("2026-07-01T08:30:00Z"))),
      List("since" -> "2026-07-01T08:30:00Z"),
    )

  test("sub-second precision is truncated, so a cursor is byte-identical between runs"):
    assertEquals(
      IssueQueries.issues(IssueQuery.Empty.updatedBefore(Instant.parse("2026-07-01T08:30:00.123456Z"))),
      List("before" -> "2026-07-01T08:30:00Z"),
    )

  test("the two login filters keep Forgejo's own snake_case spellings"):
    val query = IssueQuery.Empty.authoredBy("earl-warren").assignedTo("gusted")

    assertEquals(IssueQueries.issues(query), List("created_by" -> "earl-warren", "assigned_by" -> "gusted"))

  test("the full-text filter is Forgejo's q"):
    assertEquals(IssueQueries.issues(IssueQuery.Empty.matching("smart HTTP")), List("q" -> "smart HTTP"))

  test("a fully populated query emits every parameter once, in a stable order"):
    val query = IssueQuery.Empty
      .withState(StateFilter.Closed)
      .withLabels(Vector(Bug))
      .withMilestones(Vector(Release))
      .updatedSince(Instant.parse("2026-07-01T00:00:00Z"))
      .updatedBefore(Instant.parse("2026-08-01T00:00:00Z"))
      .authoredBy("earl-warren")
      .assignedTo("gusted")
      .matching("bye")

    assertEquals(
      IssueQueries.issues(query),
      List(
        "state"       -> "closed",
        "labels"      -> "bug",
        "q"           -> "bye",
        "milestones"  -> "Forgejo v1.18.0-0",
        "since"       -> "2026-07-01T00:00:00Z",
        "before"      -> "2026-08-01T00:00:00Z",
        "created_by"  -> "earl-warren",
        "assigned_by" -> "gusted",
      ),
    )

  test("the milestone listing always states a state, since Forgejo's silent default is open only"):
    assertEquals(IssueQueries.milestones(StateFilter.All), List("state" -> "all"))
    assertEquals(IssueQueries.milestones(StateFilter.Open), List("state" -> "open"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
