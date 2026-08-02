package com.worxbend.codeberg4s.pulls

import munit.FunSuite

import java.util.Locale

/** [[ReviewState]] is the one enum in this library whose wire values are '''upper''' case with underscores, while
  * everything around it is lowercase. Getting that backwards would still compile and would still parse the golden
  * `pull/reviews-list.json` payload — which spells its states `REQUEST_REVIEW` and `APPROVED` — only because parsing is
  * deliberately case-insensitive. The literal assertions below are what actually pins the casing.
  */
final class ReviewStateSuite extends FunSuite:

  test("approved is APPROVED, upper case"):
    assertEquals(ReviewState.Approved.wireValue, "APPROVED")

  test("requesting changes is REQUEST_CHANGES, underscored and plural"):
    assertEquals(ReviewState.RequestChanges.wireValue, "REQUEST_CHANGES")

  test("a plain remark is COMMENT"):
    assertEquals(ReviewState.Comment.wireValue, "COMMENT")

  test("a review request is REQUEST_REVIEW, and is itself a review state"):
    assertEquals(ReviewState.RequestReview.wireValue, "REQUEST_REVIEW")

  test("an unsent draft is PENDING"):
    assertEquals(ReviewState.Pending.wireValue, "PENDING")

  test("the enum is exactly Forgejo's five ReviewStateType constants"):
    assertEquals(
      ReviewState.values.toList.map(_.wireValue).sorted,
      List("APPROVED", "COMMENT", "PENDING", "REQUEST_CHANGES", "REQUEST_REVIEW"),
    )

  test("every spelling is upper case, unlike every other enum on this endpoint"):
    ReviewState.values.foreach: state =>
      assertEquals(state.wireValue, state.wireValue.toUpperCase(Locale.ROOT))

  test("no separator is a hyphen, because Forgejo underscores these two"):
    ReviewState.values.foreach(state => assert(!state.wireValue.contains("-"), s"${state.wireValue} gained a hyphen"))

  test("every state round-trips from its own wire value"):
    val roundTripped = ReviewState.values.toList.map(state => ReviewState.parse(state.wireValue))

    assertEquals(roundTripped, ReviewState.values.toList.map(Some.apply))

  test("the two states golden/pull/reviews-list.json actually shows parse to the cases they name"):
    assertEquals(ReviewState.parse("REQUEST_REVIEW"), Some(ReviewState.RequestReview))
    assertEquals(ReviewState.parse("APPROVED"), Some(ReviewState.Approved))

  test("parsing is case-insensitive, because only observation claims the casing"):
    assertEquals(ReviewState.parse("approved"), Some(ReviewState.Approved))
    assertEquals(ReviewState.parse("Request_Changes"), Some(ReviewState.RequestChanges))

  test("parsing trims, so surrounding whitespace does not cost a review"):
    assertEquals(ReviewState.parse("  PENDING\n"), Some(ReviewState.Pending))

  test("the empty state Forgejo sends for a stateless review is absent, not a failure"):
    assertEquals(ReviewState.parse(""), None)

  test("a state this library has not seen is absent rather than failing the whole page"):
    assertEquals(ReviewState.parse("DISMISSED"), None)

  test("a near miss is absent rather than being rounded to the case it resembles"):
    assertEquals(ReviewState.parse("REQUEST-REVIEW"), None)
    assertEquals(ReviewState.parse("APPROVE"), None)
