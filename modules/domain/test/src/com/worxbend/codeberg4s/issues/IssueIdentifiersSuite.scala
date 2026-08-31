package com.worxbend.codeberg4s.issues

import munit.FunSuite

/** The four numeric identifiers this group owns, and the one rule they share.
  *
  * They are tested together rather than in four near-identical suites because the behaviour under test is
  * [[com.worxbend.codeberg4s.PositiveId]]'s; what differs between them is only the field name a rejection reports, and
  * a caller branches on that name.
  */
final class IssueIdentifiersSuite extends FunSuite:

  test("an issue number is accepted from one upwards"):
    assertEquals(IssueNumber.from(1L).map(_.value), Right(1L))
    assertEquals(IssueNumber.from(13731L).map(_.value), Right(13731L))

  test("issue number zero is rejected rather than sent as /issues/0"):
    assertEquals(IssueNumber.from(0L).left.map(_.field), Left("issueNumber"))

  test("a negative issue number is rejected"):
    assertEquals(IssueNumber.from(-1L).left.map(_.field), Left("issueNumber"))

  test("a label id is accepted and reports its own field name when rejected"):
    assertEquals(LabelId.from(171368L).map(_.value), Right(171368L))
    assertEquals(LabelId.from(0L).left.map(_.field), Left("labelId"))

  test("a milestone id is accepted and reports its own field name when rejected"):
    assertEquals(MilestoneId.from(3109L).map(_.value), Right(3109L))
    assertEquals(MilestoneId.from(0L).left.map(_.field), Left("milestoneId"))

  test("a comment id is accepted and reports its own field name when rejected"):
    assertEquals(CommentId.from(20366420L).map(_.value), Right(20366420L))
    assertEquals(CommentId.from(0L).left.map(_.field), Left("commentId"))

  test("the rejection message says what would have been acceptable"):
    assertEquals(IssueNumber.from(0L).left.map(_.message), Left("must be at least 1"))
