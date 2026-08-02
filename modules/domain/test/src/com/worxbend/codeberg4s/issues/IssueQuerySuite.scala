package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import java.time.Instant

/** [[IssueQuery]]'s builders: each one sets exactly the filter it names and leaves the rest absent. */
final class IssueQuerySuite extends FunSuite:

  private val Since: Instant = Instant.parse("2026-07-01T00:00:00Z")

  private val Bug: LabelName = orFail(LabelName.from("bug"))

  private val Release: MilestoneTitle = orFail(MilestoneTitle.from("v1.0"))

  test("the empty query carries no filter at all, so Forgejo's own default applies"):
    assertEquals(IssueQuery.Empty.state, None)
    assertEquals(IssueQuery.Empty.labels, Vector.empty[LabelName])
    assertEquals(IssueQuery.Empty.milestones, Vector.empty[MilestoneTitle])
    assertEquals(IssueQuery.Empty.since, None)
    assertEquals(IssueQuery.Empty.before, None)
    assertEquals(IssueQuery.Empty.createdBy, None)
    assertEquals(IssueQuery.Empty.assignedBy, None)
    assertEquals(IssueQuery.Empty.text, None)

  test("withState is how a caller asks for closed issues, since omitting it means open"):
    assertEquals(IssueQuery.Empty.withState(StateFilter.Closed).state, Some(StateFilter.Closed))

  test("a builder leaves every other filter untouched"):
    val query = IssueQuery.Empty.withLabels(Vector(Bug))

    assertEquals(query.labels, Vector(Bug))
    assertEquals(query.state, None)
    assertEquals(query.text, None)

  test("builders compose, and the last call for a filter wins"):
    val query = IssueQuery.Empty.withState(StateFilter.All).withState(StateFilter.Open).authoredBy("earl-warren")

    assertEquals(query.state, Some(StateFilter.Open))
    assertEquals(query.createdBy, Some("earl-warren"))

  test("updatedSince and updatedBefore set the two ends of the window separately"):
    assertEquals(IssueQuery.Empty.updatedSince(Since).since, Some(Since))
    assertEquals(IssueQuery.Empty.updatedBefore(Since).before, Some(Since))

  test("assignedTo sets Forgejo's assigned_by filter, which is really an assignee filter"):
    assertEquals(IssueQuery.Empty.assignedTo("earl-warren").assignedBy, Some("earl-warren"))

  test("matching sets the full-text filter"):
    assertEquals(IssueQuery.Empty.matching("smart HTTP").text, Some("smart HTTP"))

  test("an empty vector removes a list filter rather than asking for nothing"):
    assertEquals(IssueQuery.Empty.withLabels(Vector(Bug)).withLabels(Vector.empty).labels, Vector.empty[LabelName])

  test("withMilestones restricts by title and leaves the label filter alone, since both are comma-joined"):
    val query = IssueQuery.Empty.withLabels(Vector(Bug)).withMilestones(Vector(Release))

    assertEquals(query.milestones, Vector(Release))
    assertEquals(query.labels, Vector(Bug))

  test("an empty milestone vector removes the filter rather than asking for issues with no milestone"):
    assertEquals(IssueQuery.Empty.withMilestones(Vector(Release)).withMilestones(Vector.empty).milestones, Vector.empty)

  test("each query builder sets its own filter and leaves every sibling alone"):
    val filtered = populated

    assertEquals(filtered.withState(StateFilter.Closed), filtered.copy(state = Some(StateFilter.Closed)))
    assertEquals(filtered.withLabels(Vector.empty), filtered.copy(labels = Vector.empty))
    assertEquals(filtered.withMilestones(Vector.empty), filtered.copy(milestones = Vector.empty))
    assertEquals(filtered.updatedSince(Since), filtered.copy(since = Some(Since)))
    assertEquals(filtered.updatedBefore(Since), filtered.copy(before = Some(Since)))
    assertEquals(filtered.authoredBy("crystal"), filtered.copy(createdBy = Some("crystal")))
    assertEquals(filtered.assignedTo("crystal"), filtered.copy(assignedBy = Some("crystal")))
    assertEquals(filtered.matching("smart HTTP"), filtered.copy(text = Some("smart HTTP")))

  test("each state filter has its own wire spelling"):
    assertEquals(StateFilter.Open.wireValue, "open")
    assertEquals(StateFilter.Closed.wireValue, "closed")
    assertEquals(StateFilter.All.wireValue, "all")

  private def populated: IssueQuery =
    IssueQuery(
      state      = Some(StateFilter.Open),
      labels     = Vector(Bug),
      milestones = Vector(Release),
      since      = Some(Instant.parse("2026-06-01T00:00:00Z")),
      before     = Some(Instant.parse("2026-06-30T00:00:00Z")),
      createdBy  = Some("earl-warren"),
      assignedBy = Some("earl-warren"),
      text       = Some("original"),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
