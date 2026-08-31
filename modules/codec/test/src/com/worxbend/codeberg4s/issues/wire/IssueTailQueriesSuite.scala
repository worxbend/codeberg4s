package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.CommentQuery
import com.worxbend.codeberg4s.issues.IssueKind
import com.worxbend.codeberg4s.issues.IssueSearchQuery
import com.worxbend.codeberg4s.issues.IssueSearchSort
import com.worxbend.codeberg4s.issues.LabelName
import com.worxbend.codeberg4s.issues.MilestoneTitle
import com.worxbend.codeberg4s.issues.StateFilter
import com.worxbend.codeberg4s.issues.TrackedTimeQuery
import com.worxbend.codeberg4s.issues.UploadAttachment

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.time.Instant

/** The four query strings the rest of the issue surface sends.
  *
  * The property under test throughout is that an '''unset''' filter contributes no parameter at all. That is not
  * fussiness: omitting `state` gets Forgejo's default of open issues while `state=` gets a `422`, and a malformed
  * timestamp comes back carrying a raw Go parse error (`docs/HAZARDS.md` §4).
  */
final class IssueTailQueriesSuite extends FunSuite:

  private val Since: Instant = Instant.parse("2026-07-01T00:00:00Z")

  private val Before: Instant = Instant.parse("2026-08-01T00:00:00Z")

  test("an empty search sends nothing, so the instance applies its own defaults"):
    assertEquals(IssueQueries.search(IssueSearchQuery.Empty), Nil)

  test("the search emits its filters in the order the spec declares them"):
    val query = IssueSearchQuery.Empty
      .withState(StateFilter.All)
      .withLabels(Vector(orFail(LabelName.from("bug")), orFail(LabelName.from("upstream"))))
      .withMilestones(Vector(orFail(MilestoneTitle.from("v1.0"))))
      .matching("crash")
      .prioritising(77L)
      .onlyOf(IssueKind.Issues)
      .updatedSince(Since)
      .updatedBefore(Before)
      .ownedBy(orFail(Owner.from("forgejo")))
      .inTeam("reviewers")
      .sortedBy(IssueSearchSort.NearDueDate)

    assertEquals(
      IssueQueries.search(query),
      List(
        "state"            -> "all",
        "labels"           -> "bug,upstream",
        "milestones"       -> "v1.0",
        "q"                -> "crash",
        "priority_repo_id" -> "77",
        "type"             -> "issues",
        "since"            -> "2026-07-01T00:00:00Z",
        "before"           -> "2026-08-01T00:00:00Z",
        "owner"            -> "forgejo",
        "team"             -> "reviewers",
        "sort"             -> "nearduedate",
      ),
    )

  test("only a true boolean is emitted, because the spec gives each of the five a default of false"):
    val query = IssueSearchQuery.Empty.assignedToMe.mentioningMe.reviewedByMe

    assertEquals(
      IssueQueries.search(query),
      List("assigned" -> "true", "mentioned" -> "true", "reviewed" -> "true"),
    )

  test("the two review filters carry their own wire spellings"):
    assertEquals(
      IssueQueries.search(IssueSearchQuery.Empty.awaitingMyReview),
      List("review_requested" -> "true"),
    )

  test("every sort the spec enumerates has a wire spelling, and none of them is punctuated"):
    assertEquals(
      IssueSearchSort.values.toList.map(_.wireValue),
      List(
        "relevance",
        "latest",
        "oldest",
        "recentupdate",
        "leastupdate",
        "mostcomment",
        "leastcomment",
        "nearduedate",
        "farduedate",
      ),
    )

  test("both issue kinds have a wire spelling"):
    assertEquals(IssueKind.values.toList.map(_.wireValue), List("issues", "pulls"))

  test("an empty comment window sends nothing"):
    assertEquals(IssueQueries.comments(CommentQuery.Empty), Nil)

  test("a comment window sends since before before, in the spec's order"):
    assertEquals(
      IssueQueries.comments(CommentQuery.Empty.updatedSince(Since).updatedBefore(Before)),
      List("since" -> "2026-07-01T00:00:00Z", "before" -> "2026-08-01T00:00:00Z"),
    )

  test("an empty tracked-time query sends nothing"):
    assertEquals(IssueQueries.trackedTimes(TrackedTimeQuery.Empty), Nil)

  test("a tracked-time query sends user, since and before in the spec's order"):
    val query = TrackedTimeQuery.Empty.forUser("jkassel").recordedSince(Since).recordedBefore(Before)

    assertEquals(
      IssueQueries.trackedTimes(query),
      List("user" -> "jkassel", "since" -> "2026-07-01T00:00:00Z", "before" -> "2026-08-01T00:00:00Z"),
    )

  test("an upload with neither optional setting sends no query parameters at all"):
    assertEquals(IssueQueries.attachmentUpload(upload), Nil)

  test("an upload puts name and updated_at in the query string, which is where the spec declares them"):
    assertEquals(
      IssueQueries.attachmentUpload(upload.named("build.log").recordedAt(Since)),
      List("name" -> "build.log", "updated_at" -> "2026-07-01T00:00:00Z"),
    )

  private def upload: UploadAttachment =
    orFail(UploadAttachment.of("failing-run.txt", "log".getBytes(StandardCharsets.UTF_8)))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
