package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.AddTrackedTime
import com.worxbend.codeberg4s.issues.CreateMilestone
import com.worxbend.codeberg4s.issues.EditAttachment
import com.worxbend.codeberg4s.issues.EditComment
import com.worxbend.codeberg4s.issues.EditLabel
import com.worxbend.codeberg4s.issues.EditMilestone
import com.worxbend.codeberg4s.issues.IssueNumber
import com.worxbend.codeberg4s.issues.IssueRef
import com.worxbend.codeberg4s.issues.LabelColor
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.LabelName
import com.worxbend.codeberg4s.issues.LabelRef
import com.worxbend.codeberg4s.issues.LabelRemoval
import com.worxbend.codeberg4s.issues.LabelUpdate
import com.worxbend.codeberg4s.issues.ReactionContent
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.time.Instant

/** The nine request bodies the rest of the issue surface sends.
  *
  * All nine are derived from `spec/swagger.v1.json`; no golden capture of any of them exists, because every write needs
  * a token and the harvest was anonymous.
  *
  * The property under test throughout is which keys appear, not how they are spelled once they do: a key the caller
  * never set must not reach the instance, because on a `PATCH` that is the difference between "leave this alone" and
  * "set it to this".
  */
final class IssueTailRequestBodySuite extends FunSuite:

  private val Moment: Instant = Instant.parse("2026-07-01T00:00:00Z")

  private val Bug: LabelId = orFail(LabelId.from(102L))

  private val Upstream: LabelName = orFail(LabelName.from("upstream"))

  // --- EditIssueCommentOption ----------------------------------------------

  test("a comment edit always sends the body, because the spec marks it required"):
    assertEquals(EditIssueCommentOptionDto.render(orFail(EditComment.of("looks right"))), """{"body":"looks right"}""")

  test("a comment edit sends updated_at only when the caller asked, since it needs elevated permission"):
    assertEquals(
      EditIssueCommentOptionDto.render(orFail(EditComment.of("looks right")).recordedAt(Moment)),
      """{"body":"looks right","updated_at":"2026-07-01T00:00:00Z"}""",
    )

  // --- EditReactionOption ---------------------------------------------------

  test("a reaction body is one key, and the emoji is sent verbatim"):
    assertEquals(EditReactionOptionDto.render(ReactionContent.ThumbsDown), """{"content":"-1"}""")
    assertEquals(EditReactionOptionDto.render(orFail(ReactionContent.from("rocket"))), """{"content":"rocket"}""")

  // --- IssueMeta ------------------------------------------------------------

  test("an issue reference sends all three components, because a reference missing one names no issue"):
    val reference =
      IssueRef(orFail(Owner.from("forgejo")), orFail(RepoName.from("forgejo")), orFail(IssueNumber.from(42L)))

    assertEquals(IssueMetaDto.render(reference), """{"owner":"forgejo","repo":"forgejo","index":42}""")

  test("the index is a JSON integer, not a quoted string or a float"):
    val reference = IssueRef(orFail(Owner.from("a")), orFail(RepoName.from("b")), orFail(IssueNumber.from(1L)))

    assert(IssueMetaDto.render(reference).contains("\"index\":1"), IssueMetaDto.render(reference))

  // --- EditDeadlineOption ---------------------------------------------------

  test("a deadline body renders RFC-3339 with a Z offset and second precision, which is what Go parses"):
    assertEquals(
      EditDeadlineOptionDto.render(Instant.parse("2026-09-01T12:34:56.789Z")),
      """{"due_date":"2026-09-01T12:34:56Z"}""",
    )

  // --- IssueLabelsOption and DeleteLabelsOption -----------------------------

  test("labels named by id are sent as JSON integers"):
    assertEquals(IssueLabelsOptionDto.renderUpdate(LabelUpdate.byId(Vector(Bug))), """{"labels":[102]}""")

  test("labels named by name are sent as JSON strings, which is the other arm the spec allows"):
    assertEquals(IssueLabelsOptionDto.renderUpdate(LabelUpdate.byName(Vector(Upstream))), """{"labels":["upstream"]}""")

  test("a single request may mix the two spellings, which is why the array's item schema is empty"):
    val command = LabelUpdate.of(Vector(LabelRef.ById(Bug), LabelRef.ByName(Upstream)))

    assertEquals(IssueLabelsOptionDto.renderUpdate(command), """{"labels":[102,"upstream"]}""")

  test("an empty label set is still sent, because under PUT it is the instruction to clear the issue"):
    assertEquals(IssueLabelsOptionDto.renderUpdate(LabelUpdate.Empty), """{"labels":[]}""")

  test("a label update sends updated_at only when the caller asked"):
    assertEquals(
      IssueLabelsOptionDto.renderUpdate(LabelUpdate.byId(Vector(Bug)).recordedAt(Moment)),
      """{"labels":[102],"updated_at":"2026-07-01T00:00:00Z"}""",
    )

  test("a label removal that says nothing is an empty object, not an absent body"):
    assertEquals(IssueLabelsOptionDto.renderRemoval(LabelRemoval.Empty), "{}")

  test("a label removal that backdates itself sends the one key the model has"):
    assertEquals(
      IssueLabelsOptionDto.renderRemoval(LabelRemoval.Empty.recordedAt(Moment)),
      """{"updated_at":"2026-07-01T00:00:00Z"}""",
    )

  // --- AddTimeOption --------------------------------------------------------

  test("adding time always sends seconds, because that is the unit the wire has"):
    assertEquals(AddTimeOptionDto.render(orFail(AddTrackedTime.of(2.hours))), """{"time":7200}""")

  test("adding time sends the account and the timestamp only when the caller named them"):
    val command = orFail(AddTrackedTime.of(90.seconds)).attributedTo("jkassel").recordedAt(Moment)

    assertEquals(
      AddTimeOptionDto.render(command),
      """{"time":90,"user_name":"jkassel","created":"2026-07-01T00:00:00Z"}""",
    )

  // --- CreateMilestoneOption and EditMilestoneOption ------------------------

  test("a bare milestone create sends the title and nothing else"):
    assertEquals(MilestoneOptionDto.renderCreate(orFail(CreateMilestone.of("v1.0"))), """{"title":"v1.0"}""")

  test("a milestone create sends each optional field only when the caller set it"):
    val command = orFail(CreateMilestone.of("v1.0")).describedAs("hardening").dueBy(Moment).createdClosed

    assertEquals(
      MilestoneOptionDto.renderCreate(command),
      """{"title":"v1.0","description":"hardening","due_on":"2026-07-01T00:00:00Z","state":"closed"}""",
    )

  test("a milestone edit that changes nothing is an empty object, not a request that blanks the milestone"):
    assertEquals(MilestoneOptionDto.renderEdit(EditMilestone.Empty), "{}")

  test("a milestone edit sends only what it was told to change"):
    assertEquals(
      MilestoneOptionDto.renderEdit(EditMilestone.Empty.renamedTo("v1.1").reopen),
      """{"title":"v1.1","state":"open"}""",
    )

  // --- EditLabelOption ------------------------------------------------------

  test("a label edit that changes nothing is an empty object"):
    assertEquals(EditLabelOptionDto.render(EditLabel.Empty), "{}")

  test("a label edit sends the hashed colour Forgejo documents for input, not the bare one it returns"):
    assertEquals(
      EditLabelOptionDto.render(EditLabel.Empty.colouredAs(orFail(LabelColor.from("ee0701")))),
      """{"color":"#ee0701"}""",
    )

  test("a label edit can turn a flag off, which is why the two flags are Option[Boolean] and not Boolean"):
    assertEquals(
      EditLabelOptionDto.render(EditLabel.Empty.exclusive(false).archived(true)),
      """{"exclusive":false,"is_archived":true}""",
    )

  test("a label edit renames through the same validated type a create uses"):
    assertEquals(EditLabelOptionDto.render(EditLabel.Empty.renamedTo(Upstream)), """{"name":"upstream"}""")

  // --- EditAttachmentOptions ------------------------------------------------

  test("an attachment edit that changes nothing is an empty object"):
    assertEquals(EditAttachmentOptionDto.render(EditAttachment.Empty), "{}")

  test("an ordinary rename never mentions the download URL, which Forgejo accepts only on external attachments"):
    assertEquals(
      EditAttachmentOptionDto.render(EditAttachment.Empty.renamedTo("build.log")),
      """{"name":"build.log"}""",
    )

  test("the download URL is sent when the caller asked for it"):
    assertEquals(
      EditAttachmentOptionDto.render(EditAttachment.Empty.pointingAt("https://elsewhere/x")),
      """{"browser_download_url":"https://elsewhere/x"}""",
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
