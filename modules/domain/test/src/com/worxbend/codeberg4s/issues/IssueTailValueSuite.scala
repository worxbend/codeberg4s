package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.nio.charset.StandardCharsets

/** The validated values the rest of the issue surface introduces.
  *
  * Everything here is a smart constructor or a total mapping, so the suite is about what is '''refused''' and about the
  * one boundary the domain owns outright: a duration the wire cannot carry.
  */
final class IssueTailValueSuite extends FunSuite:

  // --- identifiers ----------------------------------------------------------

  test("an attachment id and a tracked-time id both refuse anything below one"):
    assertEquals(AttachmentId.from(0L).map(_.value), Left(ValidationError("attachmentId", "must be at least 1")))
    assertEquals(TrackedTimeId.from(-1L).map(_.value), Left(ValidationError("trackedTimeId", "must be at least 1")))

  test("a positive identifier survives the round trip unchanged"):
    assertEquals(AttachmentId.from(88L).map(_.value), Right(88L))
    assertEquals(TrackedTimeId.from(474L).map(_.value), Right(474L))

  test("a pin position is one-based, because Forgejo's own pin_order uses zero for 'not pinned'"):
    assertEquals(PinPosition.from(0).map(_.value), Left(ValidationError("pinPosition", "must be at least 1")))
    assertEquals(PinPosition.from(1).map(_.value), Right(1))
    assertEquals(PinPosition.First.value, 1)

  // --- reactions ------------------------------------------------------------

  test("a reaction is trimmed and otherwise taken as given, because the vocabulary belongs to the instance"):
    assertEquals(ReactionContent.from("  rocket ").map(_.value), Right("rocket"))
    assertEquals(ReactionContent.from("🎉").map(_.value), Right("🎉"))

  test("a blank reaction and one carrying a control character are both refused"):
    assert(ReactionContent.from("   ").isLeft)
    assert(ReactionContent.from("+\u00071").isLeft)

  test("surrounding whitespace is trimmed before the control-character check, so padding alone is not a rejection"):
    assertEquals(ReactionContent.from("\t+1\n").map(_.value), Right("+1"))

  test("the two constants spell what Forgejo's own UI spells"):
    assertEquals(ReactionContent.ThumbsUp.value, "+1")
    assertEquals(ReactionContent.ThumbsDown.value, "-1")

  // --- attachment kinds -----------------------------------------------------

  test("reading a kind is total: an unknown word is kept rather than rejected"):
    assertEquals(AttachmentKind.from("attachment"), AttachmentKind.Uploaded)
    assertEquals(AttachmentKind.from(" EXTERNAL "), AttachmentKind.External)
    assertEquals(AttachmentKind.from("lfs"), AttachmentKind.Other("lfs"))

  test("every kind renders back to the word it came from"):
    assertEquals(AttachmentKind.Uploaded.wireValue, "attachment")
    assertEquals(AttachmentKind.External.wireValue, "external")
    assertEquals(AttachmentKind.Other("lfs").wireValue, "lfs")

  test("an attachment kind is lower-cased on the way in, because the spec fixes its vocabulary"):
    assertEquals(AttachmentKind.from("LFS"), AttachmentKind.Other("lfs"))
    assertEquals(AttachmentKind.from("Attachment"), AttachmentKind.Uploaded)

  test("a reaction keeps its case, because its vocabulary is the instance's and a shortcode is case-sensitive"):
    assertEquals(ReactionContent.from("ROCKET").map(_.value), Right("ROCKET"))
    assertEquals(ReactionContent.from("Party_Parrot").map(_.value), Right("Party_Parrot"))

  test("the two escape hatches differ deliberately: an unknown kind is kept as a case, an unknown reaction as itself"):
    assertEquals(AttachmentKind.from("torrent"), AttachmentKind.Other("torrent"))
    assertEquals(ReactionContent.from("torrent").map(_.value), Right("torrent"))

  // --- uploads --------------------------------------------------------------

  test("an upload trims the file name and defaults its media type"):
    val upload = orFail(UploadAttachment.of("  build.log ", bytes))

    assertEquals(upload.fileName, "build.log")
    assertEquals(upload.mediaType, UploadAttachment.DefaultMediaType)
    assertEquals(upload.size, 3)

  test("a file name that could inject a Content-Disposition header is refused at construction"):
    assert(UploadAttachment.of("a\"b.log", bytes).isLeft)
    assert(UploadAttachment.of("a\r\nb.log", bytes).isLeft)
    assert(UploadAttachment.of("   ", bytes).isLeft)

  test("a slash is allowed, because a file name is a quoted header parameter and not a path segment"):
    assertEquals(UploadAttachment.of("linux/amd64.tar.gz", bytes).map(_.fileName), Right("linux/amd64.tar.gz"))

  // --- tracked time ---------------------------------------------------------

  test("a whole number of seconds is accepted"):
    assertEquals(AddTrackedTime.of(2.hours).map(_.spent), Right(2.hours))
    assertEquals(AddTrackedTime.of(1.second).map(_.spent), Right(1.second))

  test("a duration finer than a second is refused rather than silently truncated"):
    assert(AddTrackedTime.of(1500.millis).isLeft)

  test("a zero or negative duration is refused, because it records no work"):
    assert(AddTrackedTime.of(0.seconds).isLeft)
    assert(AddTrackedTime.of(-1.hour).isLeft)

  // --- label references -----------------------------------------------------

  test("a label reference renders to the path segment its arm implies"):
    assertEquals(LabelRef.ById(orFail(LabelId.from(102L))).pathSegment, "102")
    assertEquals(LabelRef.ByName(orFail(LabelName.from("bug"))).pathSegment, "bug")

  test("the three ways of building a label update all reach the same shape"):
    val byId   = LabelUpdate.byId(Vector(orFail(LabelId.from(102L))))
    val byName = LabelUpdate.byName(Vector(orFail(LabelName.from("bug"))))
    val mixed  = LabelUpdate.of(byId.labels ++ byName.labels)

    assertEquals(byId.labels.size, 1)
    assertEquals(byName.labels.size, 1)
    assertEquals(mixed.labels.size, 2)
    assertEquals(LabelUpdate.Empty.labels, Vector.empty[LabelRef])

  test("neither label command backdates itself unless asked"):
    assertEquals(LabelUpdate.Empty.updatedAt, None)
    assertEquals(LabelRemoval.Empty.updatedAt, None)

  // --- commands that refuse a blank ----------------------------------------

  test("a comment edit and a milestone create both refuse a blank text and trim what they keep"):
    assert(EditComment.of("   ").isLeft)
    assert(CreateMilestone.of("\t").isLeft)
    assertEquals(EditComment.of("  looks right ").map(_.body), Right("looks right"))
    assertEquals(CreateMilestone.of("  v1.0 ").map(_.title), Right("v1.0"))

  test("an empty edit changes nothing, which is a well-formed request and not an error"):
    assertEquals(EditLabel.Empty.name, None)
    assertEquals(EditMilestone.Empty.title, None)
    assertEquals(EditAttachment.Empty.browserDownloadUrl, None)

  private def bytes: Array[Byte] = "log".getBytes(StandardCharsets.UTF_8)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
