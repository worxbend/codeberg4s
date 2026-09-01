package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.issues.TimelineEvent

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.time.Instant

/** [[TimelineCommentDto]] against payloads written by hand from `spec/swagger.v1.json`.
  *
  * '''No golden fixture covers the timeline.''' `golden/issue/comments-list.json` is the plain comment endpoint, which
  * returns a different and much smaller model, so these payloads are the spec's `TimelineComment` definition read
  * literally.
  *
  * Two properties are under test: the `docs/HAZARDS.md` §1 rule that present, JSON `null` and absent decode alike; and
  * that each of the nine embedded models reports a failure at its own path rather than at the entry's.
  */
final class TimelineCommentDtoSuite extends FunSuite:

  private val Full: String =
    """{
      |  "id": 9,
      |  "type": "milestone",
      |  "body": "moved",
      |  "user": {"id": 1, "login": "actor"},
      |  "assignee": {"id": 2, "login": "assigned"},
      |  "removed_assignee": true,
      |  "label": {"id": 102, "name": "bug"},
      |  "milestone": {"id": 3109, "title": "v1.1", "state": "open"},
      |  "old_milestone": {"id": 3108, "title": "v1.0", "state": "closed"},
      |  "old_title": "before",
      |  "new_title": "after",
      |  "old_ref": "main",
      |  "new_ref": "next",
      |  "ref_action": "closes",
      |  "ref_commit_sha": "1bdb1938",
      |  "ref_issue": {"id": 5, "number": 42, "title": "other", "state": "open"},
      |  "ref_comment": {"id": 77, "body": "see also"},
      |  "dependent_issue": {"id": 6, "number": 43, "title": "blocked", "state": "open"},
      |  "resolve_doer": {"id": 3, "login": "resolver"},
      |  "review_id": 11,
      |  "project_id": 12,
      |  "old_project_id": 13,
      |  "tracked_time": {"id": 474, "time": 3600},
      |  "html_url": "https://forge.example/Codeberg/Community/issues/2966#issuecomment-9",
      |  "issue_url": "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966",
      |  "pull_request_url": "",
      |  "created_at": "2026-07-31T17:20:04+02:00",
      |  "updated_at": "2026-07-31T18:20:04+02:00"
      |}""".stripMargin

  private val Nulled: String =
    """{
      |  "id": 9,
      |  "type": null,
      |  "body": null,
      |  "user": null,
      |  "assignee": null,
      |  "removed_assignee": null,
      |  "label": null,
      |  "milestone": null,
      |  "old_milestone": null,
      |  "old_title": null,
      |  "new_title": null,
      |  "old_ref": null,
      |  "new_ref": null,
      |  "ref_action": null,
      |  "ref_commit_sha": null,
      |  "ref_issue": null,
      |  "ref_comment": null,
      |  "dependent_issue": null,
      |  "resolve_doer": null,
      |  "review_id": null,
      |  "project_id": null,
      |  "old_project_id": null,
      |  "tracked_time": null,
      |  "html_url": null,
      |  "issue_url": null,
      |  "pull_request_url": null,
      |  "created_at": null,
      |  "updated_at": null
      |}""".stripMargin

  private val Minimal: String = """{"id": 9}"""

  test("every declared field decodes when present"):
    val event = decoded(Full)

    assertEquals(event.id.value, 9L)
    assertEquals(event.eventType, Some("milestone"))
    assertEquals(event.body, Some("moved"))
    assertEquals(event.author.map(_.login.value), Some("actor"))
    assertEquals(event.assignee.map(_.login.value), Some("assigned"))
    assertEquals(event.removedAssignee, true)
    assertEquals(event.label.map(_.name), Some("bug"))
    assertEquals(event.milestone.map(_.title), Some("v1.1"))
    assertEquals(event.oldMilestone.map(_.title), Some("v1.0"))
    assertEquals(event.oldTitle, Some("before"))
    assertEquals(event.newTitle, Some("after"))
    assertEquals(event.oldRef, Some("main"))
    assertEquals(event.newRef, Some("next"))
    assertEquals(event.refAction, Some("closes"))
    assertEquals(event.refCommitSha, Some("1bdb1938"))
    assertEquals(event.refIssue.map(_.number.value), Some(42L))
    assertEquals(event.refComment.map(_.id.value), Some(77L))
    assertEquals(event.dependentIssue.map(_.number.value), Some(43L))
    assertEquals(event.resolvedBy.map(_.login.value), Some("resolver"))
    assertEquals(event.reviewId, Some(11L))
    assertEquals(event.projectId, Some(12L))
    assertEquals(event.oldProjectId, Some(13L))
    assertEquals(event.trackedTime.map(_.spent), Some(1.hour))
    assertEquals(event.createdAt, Some(Instant.parse("2026-07-31T15:20:04Z")))
    assertEquals(event.updatedAt, Some(Instant.parse("2026-07-31T16:20:04Z")))

  test("Forgejo's empty-string-for-absent is folded away, so a plain issue event has no pull-request URL"):
    assertEquals(decoded(Full).pullRequestUrl, None)

  test("JSON null and an absent key decode identically, for all twenty-seven optional fields"):
    assertEquals(decoded(Nulled), decoded(Minimal))

  test("an absent removed_assignee is false, which is the reading that matches the field's name"):
    assertEquals(decoded(Minimal).removedAssignee, false)

  test("the discriminator is optional on purpose, so one odd entry does not cost a caller the page"):
    assertEquals(decoded(Minimal).eventType, None)

  test("the discriminator is kept verbatim and unmapped, whatever it says"):
    assertEquals(decoded("""{"id":1,"type":"pull_push"}""").eventType, Some("pull_push"))

  test("an entry with no id is a decoding failure at $.id"):
    failureAt("""{"type":"comment"}""", "$.id")

  test("a failure inside an embedded issue is reported at that field's path, not at the entry"):
    failureAt("""{"id":1,"ref_issue":{"id":5,"title":"t","state":"open"}}""", "$.ref_issue.number")

  test("a failure inside the embedded tracked time is reported at $.tracked_time"):
    failureAt("""{"id":1,"tracked_time":{"id":474}}""", "$.tracked_time.time")

  test("a failure inside the embedded label is reported at $.label"):
    failureAt("""{"id":1,"label":{"name":"bug"}}""", "$.label.id")

  test("an assignee_team the DTO deliberately does not model does not break the decode"):
    assertEquals(decoded("""{"id":1,"assignee_team":{"id":7,"name":"reviewers"}}""").id.value, 1L)

  test("a failing element of a listing reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[TimelineCommentDto]]("""[{"id":1},{"type":"comment"}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].id")
      case Right(value)  => fail(s"expected a failure, converted $value")

  private def decoded(body: String): TimelineEvent =
    Json.decode[TimelineCommentDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the timeline entry: $failure")

  private def failureAt(body: String, path: String): Unit =
    Json.decode[TimelineCommentDto](body).flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, path)
      case Right(value)  => fail(s"expected a failure, converted $value")
