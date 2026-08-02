package com.worxbend.codeberg4s.pulls

import munit.FunSuite

/** [[PullRequestSort]] is the one enum whose spellings are run together without a separator. A misspelled `sort` is not
  * rejected by Forgejo, it is ignored, so the caller silently gets the instance's default ordering — which is why every
  * spelling is asserted literally rather than by round trip.
  */
final class PullRequestSortSuite extends FunSuite:

  test("oldest is spelled oldest"):
    assertEquals(PullRequestSort.Oldest.wireValue, "oldest")

  test("recentupdate is run together, with neither a hyphen nor an underscore"):
    assertEquals(PullRequestSort.RecentUpdate.wireValue, "recentupdate")

  test("recentclose is run together"):
    assertEquals(PullRequestSort.RecentClose.wireValue, "recentclose")

  test("leastupdate is run together"):
    assertEquals(PullRequestSort.LeastUpdate.wireValue, "leastupdate")

  test("mostcomment is run together and singular, not mostcomments"):
    assertEquals(PullRequestSort.MostComment.wireValue, "mostcomment")

  test("leastcomment is run together and singular"):
    assertEquals(PullRequestSort.LeastComment.wireValue, "leastcomment")

  test("priority is spelled priority"):
    assertEquals(PullRequestSort.Priority.wireValue, "priority")

  test("the enum is exactly Forgejo's seven orderings, with no case for the default"):
    assertEquals(
      PullRequestSort.values.toList.map(_.wireValue).sorted,
      List(
        "leastcomment",
        "leastupdate",
        "mostcomment",
        "oldest",
        "priority",
        "recentclose",
        "recentupdate",
      ),
    )

  test("no ordering carries a separator, which is the mistake this enum exists to prevent"):
    PullRequestSort.values.foreach: ordering =>
      assert(!ordering.wireValue.contains("-"), s"${ordering.wireValue} gained a hyphen")
      assert(!ordering.wireValue.contains("_"), s"${ordering.wireValue} gained an underscore")

  test("no two orderings share a wire value"):
    val spellings = PullRequestSort.values.toList.map(_.wireValue)

    assertEquals(spellings.distinct.length, spellings.length)
