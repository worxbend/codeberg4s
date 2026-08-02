package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import java.time.Instant

/** The four command types: what their smart constructors reject, and what their builders leave alone. */
final class IssueCommandSuite extends FunSuite:

  private val Friday: Instant = Instant.parse("2026-08-07T17:00:00Z")

  private val Bug: LabelId = orFail(LabelId.from(102L))

  private val Release: MilestoneId = orFail(MilestoneId.from(3109L))

  test("an issue command starts from a title and nothing else"):
    val command = orFail(CreateIssue.of("the build is red"))

    assertEquals(command.title, "the build is red")
    assertEquals(command.body, None)
    assertEquals(command.assignees, Vector.empty[String])
    assertEquals(command.labels, Vector.empty[LabelId])
    assertEquals(command.milestone, None)
    assertEquals(command.dueDate, None)
    assertEquals(command.ref, None)
    assertEquals(command.closed, false)

  test("a title is trimmed"):
    assertEquals(CreateIssue.of("  the build is red  ").map(_.title), Right("the build is red"))

  test("a blank title is rejected here rather than costing a round trip"):
    assertEquals(CreateIssue.of("   ").left.map(_.field), Left("title"))

  test("create builders each set one field and compose"):
    val command = orFail(CreateIssue.of("t")).withBody("b").labelled(Vector(Bug)).inMilestone(Release).dueBy(Friday)

    assertEquals(command.body, Some("b"))
    assertEquals(command.labels, Vector(Bug))
    assertEquals(command.milestone, Some(Release))
    assertEquals(command.dueDate, Some(Friday))
    assertEquals(command.assignees, Vector.empty[String])

  test("createdClosed is how an import files an already-closed issue"):
    assertEquals(orFail(CreateIssue.of("t")).createdClosed.closed, true)

  test("every create builder sets its own field and leaves every sibling alone"):
    val created = populatedCreate

    assertEquals(created.withBody("replaced"), created.copy(body = Some("replaced")))
    assertEquals(created.assignedTo(Vector("crystal")), created.copy(assignees = Vector("crystal")))
    assertEquals(created.labelled(Vector.empty), created.copy(labels = Vector.empty))
    assertEquals(created.inMilestone(Release), created.copy(milestone = Some(Release)))
    assertEquals(created.dueBy(Friday), created.copy(dueDate = Some(Friday)))
    assertEquals(created.onRef("refs/heads/main"), created.copy(ref = Some("refs/heads/main")))
    assertEquals(created.createdClosed, created.copy(closed = true))

  test("assignedTo replaces the whole list on a create, so an empty vector leaves the issue unassigned"):
    assertEquals(populatedCreate.assignedTo(Vector.empty).assignees, Vector.empty[String])

  test("every edit builder sets its own field and leaves every sibling alone"):
    val edit = populatedEdit

    assertEquals(edit.withTitle("replaced"), edit.copy(title = Some("replaced")))
    assertEquals(edit.withBody("replaced"), edit.copy(body = Some("replaced")))
    assertEquals(edit.assignedTo(Vector("crystal")), edit.copy(assignees = Some(Vector("crystal"))))
    assertEquals(edit.inMilestone(Release), edit.copy(milestone = Some(Release)))
    assertEquals(edit.close, edit.copy(state = Some(IssueStateChange.Close)))
    assertEquals(edit.reopen, edit.copy(state = Some(IssueStateChange.Reopen)))
    assertEquals(edit.dueBy(Friday), edit.copy(dueDate = Some(Friday)))
    assertEquals(edit.withoutDueDate, edit.copy(unsetDueDate = true))
    assertEquals(edit.onRef("refs/heads/main"), edit.copy(ref = Some("refs/heads/main")))

  test("setting a deadline on an edit does not raise the unset flag, which would clear it again"):
    val deadlined = EditIssue.Empty.dueBy(Friday)

    assertEquals(deadlined.dueDate, Some(Friday))
    assertEquals(deadlined.unsetDueDate, false)

  test("an untouched assignee list stays absent, which is what leaves the assignees alone"):
    assertEquals(EditIssue.Empty.withTitle("t").assignees, None)

  test("an empty edit changes nothing, and every field says so"):
    assertEquals(EditIssue.Empty.title, None)
    assertEquals(EditIssue.Empty.assignees, None)
    assertEquals(EditIssue.Empty.state, None)
    assertEquals(EditIssue.Empty.unsetDueDate, false)

  test("assignedTo with an empty vector is present-and-empty, which is how a caller unassigns everyone"):
    assertEquals(EditIssue.Empty.assignedTo(Vector.empty).assignees, Some(Vector.empty[String]))

  test("close and reopen set opposite transitions, and the later call wins"):
    assertEquals(EditIssue.Empty.close.state, Some(IssueStateChange.Close))
    assertEquals(EditIssue.Empty.close.reopen.state, Some(IssueStateChange.Reopen))

  test("clearing a deadline is its own flag, not a deadline of some sentinel value"):
    assertEquals(EditIssue.Empty.withoutDueDate.unsetDueDate, true)
    assertEquals(EditIssue.Empty.withoutDueDate.dueDate, None)

  test("a state change spells itself the way Forgejo's state field expects"):
    assertEquals(IssueStateChange.Reopen.wireValue, "open")
    assertEquals(IssueStateChange.Close.wireValue, "closed")

  test("a comment command trims its body and rejects a blank one"):
    assertEquals(CreateComment.of("  looks right to me  ").map(_.body), Right("looks right to me"))
    assertEquals(CreateComment.of("\n\t ").left.map(_.field), Left("body"))

  test("a label command needs no further validation, because both required fields are already validated types"):
    val command = CreateLabel.of(orFail(LabelName.from("bug")), orFail(LabelColor.from("#ee0701")))

    assertEquals(command.name.value, "bug")
    assertEquals(command.color.value, "ee0701")
    assertEquals(command.description, None)
    assertEquals(command.isExclusive, false)
    assertEquals(command.isArchived, false)

  test("label builders set the two flags independently"):
    val command = CreateLabel.of(orFail(LabelName.from("bug")), orFail(LabelColor.from("ee0701")))

    assertEquals(command.exclusive.isExclusive, true)
    assertEquals(command.exclusive.isArchived, false)
    assertEquals(command.archived.describedAs("stale").description, Some("stale"))

  private def populatedCreate: CreateIssue =
    orFail(CreateIssue.of("original"))
      .withBody("original body")
      .assignedTo(Vector("earl-warren"))
      .labelled(Vector(Bug))
      .inMilestone(orFail(MilestoneId.from(3110L)))
      .dueBy(Instant.parse("2026-08-10T09:00:00Z"))
      .onRef("refs/heads/next")

  private def populatedEdit: EditIssue =
    EditIssue(
      title        = Some("original"),
      body         = Some("original body"),
      assignees    = Some(Vector("earl-warren")),
      milestone    = Some(orFail(MilestoneId.from(3110L))),
      state        = Some(IssueStateChange.Reopen),
      dueDate      = Some(Instant.parse("2026-08-10T09:00:00Z")),
      unsetDueDate = false,
      ref          = Some("refs/heads/next"),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
