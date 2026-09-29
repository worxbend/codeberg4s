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
    assertEquals(IssueQuery.Empty.kind, None)
    assertEquals(IssueQuery.Empty.since, None)
    assertEquals(IssueQuery.Empty.before, None)
    assertEquals(IssueQuery.Empty.createdBy, None)
    assertEquals(IssueQuery.Empty.assignedBy, None)
    assertEquals(IssueQuery.Empty.mentionedBy, None)
    assertEquals(IssueQuery.Empty.text, None)
    assertEquals(IssueQuery.Empty.sort, None)

  test("withState is how a caller asks for closed issues, since omitting it means open"):
    assertEquals(IssueQuery.Empty.withState(StateFilter.Closed).state, Some(StateFilter.Closed))

  test("onlyOf is how a caller keeps pull requests out of an issue listing, since omitting it means both"):
    assertEquals(IssueQuery.Empty.onlyOf(IssueKind.Issues).kind, Some(IssueKind.Issues))
    assertEquals(IssueQuery.Empty.onlyOf(IssueKind.Pulls).kind, Some(IssueKind.Pulls))

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

  test("mentioning sets Forgejo's mentioned_by filter"):
    assertEquals(IssueQuery.Empty.mentioning("earl-warren").mentionedBy, Some("earl-warren"))

  test("sortedBy reuses the search ordering, because the listing declares the same nine values"):
    assertEquals(IssueQuery.Empty.sortedBy(IssueSearchSort.Oldest).sort, Some(IssueSearchSort.Oldest))

  test("matching sets the full-text filter, trimmed"):
    assertEquals(IssueQuery.Empty.matching("smart HTTP").map(_.text), Right(Some("smart HTTP")))
    assertEquals(IssueQuery.Empty.matching("  smart HTTP  ").map(_.text), Right(Some("smart HTTP")))

  test("a blank keyword clears the full-text filter rather than asking for q= empty"):
    val narrowed = orFail(IssueQuery.Empty.matching("smart HTTP"))

    assertEquals(narrowed.matching("   ").map(_.text), Right(None))

  test("a keyword carrying a control character is refused on the text field"):
    assertEquals(IssueQuery.Empty.matching("smart\nHTTP").swap.toOption.map(_.field), Some("text"))

  test("an empty vector removes a list filter rather than asking for nothing"):
    assertEquals(IssueQuery.Empty.withLabels(Vector(Bug)).withLabels(Vector.empty).labels, Vector.empty[LabelName])

  test("withMilestones restricts by title and leaves the label filter alone, since both are comma-joined"):
    val query = IssueQuery.Empty.withLabels(Vector(Bug)).withMilestones(Vector(Release))

    assertEquals(query.milestones, Vector(Release))
    assertEquals(query.labels, Vector(Bug))

  test("an empty milestone vector removes the filter rather than asking for issues with no milestone"):
    assertEquals(IssueQuery.Empty.withMilestones(Vector(Release)).withMilestones(Vector.empty).milestones, Vector.empty)

  test("a label name with a comma cannot be built, because the comma-joined filter could not carry it"):
    assertEquals(LabelName.from("a,b").swap.toOption.map(_.field), Some("labelName"))
    assertEquals(MilestoneTitle.from("v1,0").swap.toOption.map(_.field), Some("milestoneTitle"))

  test("each query builder sets its own filter and leaves every sibling alone"):
    val filtered = populated

    assertEquals(filtered.withState(StateFilter.Closed), filtered.copy(state = Some(StateFilter.Closed)))
    assertEquals(filtered.withLabels(Vector.empty), filtered.copy(labels = Vector.empty))
    assertEquals(filtered.withMilestones(Vector.empty), filtered.copy(milestones = Vector.empty))
    assertEquals(filtered.onlyOf(IssueKind.Pulls), filtered.copy(kind = Some(IssueKind.Pulls)))
    assertEquals(filtered.updatedSince(Since), filtered.copy(since = Some(Since)))
    assertEquals(filtered.updatedBefore(Since), filtered.copy(before = Some(Since)))
    assertEquals(filtered.authoredBy("crystal"), filtered.copy(createdBy = Some("crystal")))
    assertEquals(filtered.assignedTo("crystal"), filtered.copy(assignedBy = Some("crystal")))
    assertEquals(filtered.mentioning("crystal"), filtered.copy(mentionedBy = Some("crystal")))
    assertEquals(filtered.matching("smart HTTP"), Right(filtered.copy(text = Some("smart HTTP"))))
    assertEquals(filtered.sortedBy(IssueSearchSort.Oldest), filtered.copy(sort = Some(IssueSearchSort.Oldest)))

  test("each state filter has its own wire spelling"):
    assertEquals(StateFilter.Open.wireValue, "open")
    assertEquals(StateFilter.Closed.wireValue, "closed")
    assertEquals(StateFilter.All.wireValue, "all")

  private def populated: IssueQuery =
    IssueQuery(
      state       = Some(StateFilter.Open),
      labels      = Vector(Bug),
      milestones  = Vector(Release),
      kind        = Some(IssueKind.Issues),
      since       = Some(Instant.parse("2026-06-01T00:00:00Z")),
      before      = Some(Instant.parse("2026-06-30T00:00:00Z")),
      createdBy   = Some("earl-warren"),
      assignedBy  = Some("earl-warren"),
      mentionedBy = Some("earl-warren"),
      text        = Some("original"),
      sort        = Some(IssueSearchSort.Latest),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
