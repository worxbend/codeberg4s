package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.nio.charset.StandardCharsets
import java.time.Instant

/** The command and query types the issue group grew after its first pass: edits, uploads, tracked time and the two
  * listing windows.
  *
  * Each builder is applied to a value that already has '''every''' field populated and compared against that value with
  * one field replaced, so a builder that quietly reset a sibling fails here. Applying the same builder to an `Empty`
  * would prove nothing, which is why no test below does.
  *
  * The rejections are asserted one per reason rather than one per constructor: a smart constructor that answered the
  * right field with the wrong reason would still be lying to the caller.
  */
final class IssueTailCommandSuite extends FunSuite:

  private val Friday: Instant = Instant.parse("2026-08-07T17:00:00Z")

  private val Monday: Instant = Instant.parse("2026-08-10T09:00:00Z")

  private val June: Instant = Instant.parse("2026-06-01T00:00:00Z")

  private val July: Instant = Instant.parse("2026-07-01T00:00:00Z")

  private val Bug: LabelName = orFail(LabelName.from("bug"))

  private val Chore: LabelName = orFail(LabelName.from("chore"))

  private val Red: LabelColor = orFail(LabelColor.from("eb6420"))

  private val Blue: LabelColor = orFail(LabelColor.from("#0052cc"))

  private val BugId: LabelId = orFail(LabelId.from(102L))

  private val ChoreId: LabelId = orFail(LabelId.from(103L))

  // --- EditLabel ------------------------------------------------------------

  test("every label-edit builder sets its own field and leaves every sibling alone"):
    val edit = populatedLabelEdit

    assertEquals(edit.renamedTo(Chore), edit.copy(name = Some(Chore)))
    assertEquals(edit.colouredAs(Blue), edit.copy(color = Some(Blue)))
    assertEquals(edit.describedAs("replaced"), edit.copy(description = Some("replaced")))
    assertEquals(edit.exclusive(false), edit.copy(isExclusive = Some(false)))
    assertEquals(edit.archived(false), edit.copy(isArchived = Some(false)))

  test("a label edit can turn a flag off, which an absent flag cannot say and a create cannot express"):
    assertEquals(EditLabel.Empty.isExclusive, None)
    assertEquals(EditLabel.Empty.exclusive(false).isExclusive, Some(false))
    assertEquals(EditLabel.Empty.archived(false).isArchived, Some(false))

  test("recolouring a label sends no name, so a rename cannot happen by accident"):
    assertEquals(EditLabel.Empty.colouredAs(Blue).name, None)

  // --- EditMilestone --------------------------------------------------------

  test("every milestone-edit builder sets its own field and leaves every sibling alone"):
    val edit = populatedMilestoneEdit

    assertEquals(edit.renamedTo("v1.1"), edit.copy(title = Some("v1.1")))
    assertEquals(edit.describedAs("replaced"), edit.copy(description = Some("replaced")))
    assertEquals(edit.dueBy(Monday), edit.copy(dueOn = Some(Monday)))
    assertEquals(edit.close, edit.copy(state = Some(IssueStateChange.Close)))
    assertEquals(edit.reopen, edit.copy(state = Some(IssueStateChange.Reopen)))

  test("close and reopen are two directions of one field, so the last one asked for is the one sent"):
    assertEquals(EditMilestone.Empty.close.reopen.state, Some(IssueStateChange.Reopen))
    assertEquals(EditMilestone.Empty.reopen.close.state, Some(IssueStateChange.Close))

  test("a milestone edit says nothing about a deadline unless asked, because there is no way to clear one"):
    assertEquals(EditMilestone.Empty.dueOn, None)
    assertEquals(EditMilestone.Empty.renamedTo("v1.1").dueOn, None)

  // --- CreateMilestone ------------------------------------------------------

  test("every milestone-create builder sets its own field and leaves every sibling alone"):
    val command = populatedMilestoneCreate

    assertEquals(command.describedAs("replaced"), command.copy(description = Some("replaced")))
    assertEquals(command.dueBy(Monday), command.copy(dueOn = Some(Monday)))
    assertEquals(command.createdClosed, command.copy(state = Some(IssueStateChange.Close)))

  test("a fresh milestone states no lifecycle, so the instance's default of open applies"):
    assertEquals(orFail(CreateMilestone.of("v1.0")).state, None)
    assertEquals(orFail(CreateMilestone.of("v1.0")).createdClosed.state, Some(IssueStateChange.Close))

  test("a milestone may be created with a comma in its title, which the filter type refuses"):
    assertEquals(CreateMilestone.of("v1.0, hardening").map(_.title), Right("v1.0, hardening"))
    assertEquals(fieldOf(MilestoneTitle.from("v1.0, hardening")), "milestoneTitle")

  test("a blank milestone title is refused on the title field"):
    assertEquals(fieldOf(CreateMilestone.of("   ")), "title")
    assertEquals(messageOf(CreateMilestone.of("   ")), "must not be blank")

  // --- EditComment ----------------------------------------------------------

  test("backdating a comment edit leaves the replacement body alone"):
    val edit = orFail(EditComment.of("looks right"))

    assertEquals(edit.recordedAt(Monday), edit.copy(updatedAt = Some(Monday)))
    assertEquals(edit.recordedAt(Monday).body, "looks right")

  test("a comment edit is not backdated unless asked, because an ordinary token has that ignored anyway"):
    assertEquals(orFail(EditComment.of("looks right")).updatedAt, None)

  test("a blank comment body is refused on the body field, since clearing a comment is not what a PATCH is for"):
    assertEquals(fieldOf(EditComment.of("  \n\t ")), "body")

  // --- EditAttachment -------------------------------------------------------

  test("every attachment-edit builder sets its own field and leaves the other alone"):
    val edit = EditAttachment(name = Some("run.txt"), browserDownloadUrl = Some("https://old.example/run.txt"))

    assertEquals(edit.renamedTo("failing-run.txt"), edit.copy(name = Some("failing-run.txt")))
    assertEquals(
      edit.pointingAt("https://new.example/run.txt"),
      edit.copy(browserDownloadUrl = Some("https://new.example/run.txt")),
    )

  test("renaming an attachment sends no download url, which Forgejo rejects on anything but an external one"):
    assertEquals(EditAttachment.Empty.renamedTo("failing-run.txt").browserDownloadUrl, None)

  // --- LabelUpdate and LabelRemoval -----------------------------------------

  test("backdating a label update leaves the labels it names alone"):
    val update = LabelUpdate.byId(Vector(BugId, ChoreId))

    assertEquals(update.recordedAt(Monday), update.copy(updatedAt = Some(Monday)))
    assertEquals(update.recordedAt(Monday).labels.length, 2)

  test("backdating a label removal is the only thing that body can say"):
    assertEquals(LabelRemoval.Empty.recordedAt(Monday), LabelRemoval(Some(Monday)))
    assertEquals(LabelRemoval.Empty.recordedAt(Friday).recordedAt(Monday).updatedAt, Some(Monday))

  // --- UploadAttachment -----------------------------------------------------

  test("every upload builder sets its own field and leaves every sibling alone"):
    val upload = populatedUpload

    assertEquals(upload.named("failing-run.txt"), upload.copy(storedName = Some("failing-run.txt")))
    assertEquals(upload.as("application/json"), upload.copy(mediaType = "application/json"))
    assertEquals(upload.recordedAt(Monday), upload.copy(updatedAt = Some(Monday)))

  test("the stored name is a second name, and never overwrites the one the multipart part announces"):
    val upload = orFail(UploadAttachment.of("build/logs/run.txt", bytes)).named("failing-run.txt")

    assertEquals(upload.fileName, "build/logs/run.txt")
    assertEquals(upload.storedName, Some("failing-run.txt"))

  test("an upload is not backdated and carries no stored name unless the caller says so"):
    val upload = orFail(UploadAttachment.of("run.txt", bytes))

    assertEquals(upload.storedName, None)
    assertEquals(upload.updatedAt, None)

  test("the bytes are aliased rather than copied, which is the trade the type documents"):
    val payload = "log".getBytes(StandardCharsets.UTF_8)
    val upload  = orFail(UploadAttachment.of("run.txt", payload))

    assert(upload.content.eq(payload), "the upload copied the caller's array instead of aliasing it")
    assertEquals(upload.size, payload.length)

  test("a header-injecting file name and an ordinary control character are refused for different stated reasons"):
    assertEquals(messageOf(UploadAttachment.of("a\"b.log", bytes)), HeaderInjection)
    assertEquals(messageOf(UploadAttachment.of("a\nb.log", bytes)), HeaderInjection)
    assertEquals(messageOf(UploadAttachment.of(s"a${Bell}b.log", bytes)), "must not contain a control character")

  test("every file-name rejection names the fileName field, whichever check caught it"):
    assertEquals(fieldOf(UploadAttachment.of("   ", bytes)), "fileName")
    assertEquals(fieldOf(UploadAttachment.of("a\rb.log", bytes)), "fileName")
    assertEquals(fieldOf(UploadAttachment.of(s"a${Bell}b.log", bytes)), "fileName")

  // --- AddTrackedTime -------------------------------------------------------

  test("a duration one nanosecond off a whole second is refused, which a truncating check would accept"):
    assertEquals(fieldOf(AddTrackedTime.of(1.second + 1.nano)), "spent")
    assertEquals(fieldOf(AddTrackedTime.of(999999999.nanos)), "spent")
    assertEquals(
      messageOf(AddTrackedTime.of(1.second + 1.nano)),
      "must be a whole number of seconds, which is all Forgejo records",
    )

  test("a whole number of seconds survives whatever unit it was written in, to the second"):
    assertEquals(AddTrackedTime.of(2000.millis).map(_.spent.toSeconds), Right(2L))
    assertEquals(AddTrackedTime.of(90.seconds).map(_.spent.toSeconds), Right(90L))
    assertEquals(AddTrackedTime.of(2.hours).map(_.spent.toSeconds), Right(7200L))

  test("a zero or negative duration is refused as not positive, and not as an unsendable fraction"):
    assertEquals(messageOf(AddTrackedTime.of(0.seconds)), "must be a positive duration")
    assertEquals(messageOf(AddTrackedTime.of(-1.hour)), "must be a positive duration")
    assertEquals(messageOf(AddTrackedTime.of(-1500.millis)), "must be a positive duration")

  test("both tracked-time builders set their own field and leave the recorded duration alone"):
    val entry = populatedTrackedTime

    assertEquals(entry.attributedTo("earl-warren"), entry.copy(userName = Some("earl-warren")))
    assertEquals(entry.recordedAt(Monday), entry.copy(createdAt = Some(Monday)))
    assertEquals(entry.attributedTo("earl-warren").spent, 90.seconds)

  test("a new entry belongs to the authenticated account and is not backdated"):
    val entry = orFail(AddTrackedTime.of(90.seconds))

    assertEquals(entry.userName, None)
    assertEquals(entry.createdAt, None)

  // --- CommentQuery ---------------------------------------------------------

  test("each comment-window builder sets its own end of the window and leaves the other alone"):
    val window = CommentQuery(since = Some(June), before = Some(July))

    assertEquals(window.updatedSince(Friday), window.copy(since = Some(Friday)))
    assertEquals(window.updatedBefore(Monday), window.copy(before = Some(Monday)))

  test("the empty comment window sends neither end, because an empty timestamp is a 422 and an absent one is not"):
    assertEquals(CommentQuery.Empty.since, None)
    assertEquals(CommentQuery.Empty.before, None)
    assertEquals(CommentQuery.Empty.updatedSince(June).before, None)

  // --- TrackedTimeQuery -----------------------------------------------------

  test("each tracked-time filter sets its own field and leaves every sibling alone"):
    val query = TrackedTimeQuery(userName = Some("earl-warren"), since = Some(June), before = Some(July))

    assertEquals(query.forUser("crystal"), query.copy(userName = Some("crystal")))
    assertEquals(query.recordedSince(Friday), query.copy(since = Some(Friday)))
    assertEquals(query.recordedBefore(Monday), query.copy(before = Some(Monday)))

  test("the empty tracked-time query asks for everybody's entries rather than for a nameless account's"):
    assertEquals(TrackedTimeQuery.Empty.userName, None)
    assertEquals(TrackedTimeQuery.Empty.since, None)
    assertEquals(TrackedTimeQuery.Empty.before, None)

  // --- helpers --------------------------------------------------------------

  /** A BEL, built rather than written, so an invisible byte never sits in this source file. */
  private val Bell: String = 7.toChar.toString

  private val HeaderInjection: String = "must not contain a quotation mark or a line break"

  private def bytes: Array[Byte] = "log".getBytes(StandardCharsets.UTF_8)

  private def populatedLabelEdit: EditLabel =
    EditLabel(
      name        = Some(Bug),
      color       = Some(Red),
      description = Some("original"),
      isExclusive = Some(true),
      isArchived  = Some(true),
    )

  private def populatedMilestoneEdit: EditMilestone =
    EditMilestone(
      title       = Some("v1.0"),
      description = Some("original"),
      dueOn       = Some(Friday),
      state       = Some(IssueStateChange.Close),
    )

  private def populatedMilestoneCreate: CreateMilestone =
    CreateMilestone(
      title       = "v1.0",
      description = Some("original"),
      dueOn       = Some(Friday),
      state       = Some(IssueStateChange.Reopen),
    )

  private def populatedUpload: UploadAttachment =
    orFail(UploadAttachment.of("run.txt", bytes)).named("run.txt").as("text/plain").recordedAt(Friday)

  private def populatedTrackedTime: AddTrackedTime =
    orFail(AddTrackedTime.of(90.seconds)).attributedTo("crystal").recordedAt(Friday)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def fieldOf[A](result: Either[ValidationError, A]): String =
    result match
      case Left(error)  => error.field
      case Right(value) => fail(s"expected a rejection, built $value")

  private def messageOf[A](result: Either[ValidationError, A]): String =
    result match
      case Left(error)  => error.message
      case Right(value) => fail(s"expected a rejection, built $value")
