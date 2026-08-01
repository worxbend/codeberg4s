package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.CreateComment
import com.worxbend.codeberg4s.issues.CreateIssue
import com.worxbend.codeberg4s.issues.CreateLabel
import com.worxbend.codeberg4s.issues.EditIssue
import com.worxbend.codeberg4s.issues.LabelColor
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.LabelName
import com.worxbend.codeberg4s.issues.MilestoneId

import munit.FunSuite

import java.time.Instant

/** The four request bodies this group sends.
  *
  * The property under test throughout is which keys appear, not how they are spelled once they do: a key the caller
  * never set must not reach the instance, because on a `PATCH` that is the difference between "leave this alone" and
  * "set it to this".
  */
final class IssueRequestBodySuite extends FunSuite:

  private val Friday: Instant = Instant.parse("2026-08-07T17:00:00Z")

  private val Bug: LabelId = orFail(LabelId.from(102L))

  private val Upstream: LabelId = orFail(LabelId.from(11356L))

  private val Release: MilestoneId = orFail(MilestoneId.from(3109L))

  test("a bare create sends the title and nothing else"):
    assertEquals(CreateIssueOptionDto.render(issue), """{"title":"the build is red"}""")

  test("a create sends only the optional fields the caller set"):
    assertEquals(
      CreateIssueOptionDto.render(issue.withBody("since 1bdb1938")),
      """{"title":"the build is red","body":"since 1bdb1938"}""",
    )

  test("label ids are sent as JSON integers, not as quoted strings or as floats"):
    assertEquals(
      CreateIssueOptionDto.render(issue.labelled(Vector(Bug, Upstream))),
      """{"title":"the build is red","labels":[102,11356]}""",
    )

  test("an empty assignee or label vector is a statement the caller never made, so it is not sent"):
    assertEquals(
      CreateIssueOptionDto.render(issue.assignedTo(Vector.empty).labelled(Vector.empty)),
      """{"title":"the build is red"}""",
    )

  test("a milestone is sent as its id"):
    assertEquals(
      CreateIssueOptionDto.render(issue.inMilestone(Release)),
      """{"title":"the build is red","milestone":3109}""",
    )

  test("a due date is sent in the RFC-3339 form Go parses"):
    assertEquals(
      CreateIssueOptionDto.render(issue.dueBy(Friday)),
      """{"title":"the build is red","due_date":"2026-08-07T17:00:00Z"}""",
    )

  test("closed is sent only when the caller asked for it"):
    assertEquals(
      CreateIssueOptionDto.render(issue.createdClosed),
      """{"title":"the build is red","closed":true}""",
    )

  test("an empty edit is a well-formed request that changes nothing"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty), "{}")

  test("an edit sends only what it was told to change"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty.withTitle("still red")), """{"title":"still red"}""")

  test("closing an issue sends the state Forgejo expects, and no closing timestamp"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty.close), """{"state":"closed"}""")

  test("reopening sends the open spelling"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty.reopen), """{"state":"open"}""")

  test("an empty assignee list IS sent on an edit, because that is how a caller unassigns everyone"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty.assignedTo(Vector.empty)), """{"assignees":[]}""")

  test("clearing a deadline sends the flag, not a null due_date"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty.withoutDueDate), """{"unset_due_date":true}""")

  test("an unset unset_due_date is not sent as false"):
    assertEquals(EditIssueOptionDto.render(EditIssue.Empty.dueBy(Friday)), """{"due_date":"2026-08-07T17:00:00Z"}""")

  test("a comment sends its body and nothing else"):
    assertEquals(
      CreateIssueCommentOptionDto.render(orFail(CreateComment.of("looks right"))),
      """{"body":"looks right"}""",
    )

  test("a comment body is JSON-escaped rather than pasted in"):
    assertEquals(
      CreateIssueCommentOptionDto.render(orFail(CreateComment.of("""he said "no"."""))),
      """{"body":"he said \"no\"."}""",
    )

  test("a bare label create sends the name and the hashed colour Forgejo documents for input"):
    assertEquals(CreateLabelOptionDto.render(label), """{"name":"bug","color":"#ee0701"}""")

  test("the label flags are sent only when turned on"):
    assertEquals(
      CreateLabelOptionDto.render(label.exclusive.archived.describedAs("stale")),
      """{"name":"bug","color":"#ee0701","description":"stale","exclusive":true,"is_archived":true}""",
    )

  private def issue: CreateIssue =
    orFail(CreateIssue.of("the build is red"))

  private def label: CreateLabel =
    CreateLabel.of(orFail(LabelName.from("bug")), orFail(LabelColor.from("#ee0701")))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
