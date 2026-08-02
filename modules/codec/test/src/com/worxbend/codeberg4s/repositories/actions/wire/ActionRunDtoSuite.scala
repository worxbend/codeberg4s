package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.actions.ActionRun
import com.worxbend.codeberg4s.repositories.actions.ActionStatus

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.time.Instant

/** Decoding an `ActionRun`.
  *
  * '''These payloads were written by hand from `spec/swagger.v1.json`'s `ActionRun` definition, not captured from a
  * live instance.''' No Actions fixture exists — see [[ActionArtifactDto]] — so this suite is evidence that the DTO
  * matches the spec, and not evidence that Forgejo sends exactly this.
  */
final class ActionRunDtoSuite extends FunSuite:

  test("a full run decodes field for field"):
    val dto = decode(ActionRunDtoSuite.FullBody)

    assertEquals(dto.id, Some(4711L))
    assertEquals(dto.indexInRepo, Some(42L))
    assertEquals(dto.title, Some("release the hounds"))
    assertEquals(dto.status, Some("failure"))
    assertEquals(dto.workflowId, Some("build.yml"))
    assertEquals(dto.event, Some("push"))
    assertEquals(dto.triggerEvent, Some("push"))
    assertEquals(dto.prettyRef, Some("main"))

  test("the capitalised ScheduleID key is read, because reading it as schedule_id would lose it"):
    assertEquals(decode(ActionRunDtoSuite.FullBody).scheduleId, Some(7L))
    assertEquals(domain(ActionRunDtoSuite.FullBody).scheduleId, Some(7L))

  test("duration is a Go nanosecond count, not seconds"):
    assertEquals(decode(ActionRunDtoSuite.FullBody).durationNanos, Some(90000000000L))
    assertEquals(domain(ActionRunDtoSuite.FullBody).duration, Some(90.seconds))

  test("a zero duration is kept, because a run that has not started has genuinely taken no time"):
    assertEquals(converted(ActionRunDtoSuite.Bare.copy(durationNanos = Some(0L))).duration, Some(0.nanos))

  test("a negative duration is dropped, because no elapsed time is negative"):
    assertEquals(converted(ActionRunDtoSuite.Bare.copy(durationNanos = Some(-1L))).duration, None)

  test("the status becomes an enum"):
    assertEquals(domain(ActionRunDtoSuite.FullBody).status, Some(ActionStatus.Failure))

  test("a status outside the enumerated set becomes absence rather than a failure"):
    assertEquals(converted(ActionRunDtoSuite.Bare.copy(status = Some("in_progress"))).status, None)

  test("the embedded repository is projected to a slug from owner.login and name"):
    assertEquals(domain(ActionRunDtoSuite.FullBody).repository.map(_.value), Some("forgejo/forgejo"))

  test("a repository object with no nested owner falls back to full_name"):
    val body = """{"id":1,"repository":{"full_name":"Codeberg/Community"}}"""

    assertEquals(domain(body).repository.map(_.value), Some("Codeberg/Community"))

  test("a repository that cannot name an addressable repository costs a slug, not the run"):
    val body = """{"id":1,"repository":{"full_name":"one/two/three"}}"""

    assertEquals(domain(body).repository, None)
    assertEquals(domain(body).id.value, 1L)

  test("the trigger user is decoded through the shared user model"):
    assertEquals(domain(ActionRunDtoSuite.FullBody).triggerUser.map(_.login), Some("earl-warren"))

  test("a trigger user that cannot be converted fails at its own path"):
    assertEquals(failurePath("""{"id":1,"trigger_user":{"id":9}}"""), Some("$.trigger_user.login"))

  test("the timestamps are parsed, and the zero-time sentinel is folded into absence"):
    val decoded = domain(ActionRunDtoSuite.FullBody)

    assertEquals(decoded.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(decoded.startedAt, Some(Instant.parse("2026-07-30T19:14:20Z")))
    assertEquals(decoded.stoppedAt, None)

  test("a commit sha that is not an object id is dropped rather than failing the run"):
    assertEquals(converted(ActionRunDtoSuite.Bare.copy(commitSha = Some("not-a-sha!"))).commitSha, None)

  test("approved_by is zero for a run nobody approved, which is absence"):
    assertEquals(converted(ActionRunDtoSuite.Bare.copy(approvedBy = Some(0L))).approvedBy, None)
    assertEquals(converted(ActionRunDtoSuite.Bare.copy(approvedBy = Some(31L))).approvedBy, Some(31L))

  test("a run without an id cannot be converted"):
    assertEquals(failurePath("""{"title":"no id"}"""), Some("$.id"))

  test("a run whose id is not a positive identifier cannot be converted"):
    assertEquals(failurePath("""{"id":0}"""), Some("$.id"))

  test("JSON null and an absent key decode identically for every field"):
    assertEquals(decode(ActionRunDtoSuite.NullBody), decode(ActionRunDtoSuite.AbsentBody))

  test("the flags default to false when the instance says nothing"):
    val decoded = domain(ActionRunDtoSuite.AbsentBody)

    assertEquals(decoded.isForkPullRequest, false)
    assertEquals(decoded.needApproval, false)
    assertEquals(decoded.isRefDeleted, false)

  test("event_payload is decoded and deliberately not carried into the domain"):
    assertEquals(decode(ActionRunDtoSuite.FullBody).eventPayload, Some("""{"ref":"refs/heads/main"}"""))

  test("a bad element of a run array reports its own position"):
    val dtos = Json.decode[Vector[ActionRunDto]]("""[{"id":1},{"title":"no id"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(ActionRunDto.toDomainAll(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].id"))

  private def decode(body: String): ActionRunDto =
    Json.decode[ActionRunDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domain(body: String): ActionRun =
    converted(decode(body))

  private def converted(dto: ActionRunDto): ActionRun =
    dto.toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def failurePath(body: String): Option[String] =
    decode(body).toDomain.swap.toOption.map(_.path.render)

/** The payloads this suite decodes, shaped to the spec's `ActionRun` definition. */
object ActionRunDtoSuite:

  /** A run with nothing set but its identifier, for the one-field-at-a-time conversion tests. */
  private val Bare: ActionRunDto =
    ActionRunDto(
      id                = Some(1L),
      indexInRepo       = None,
      title             = None,
      status            = None,
      workflowId        = None,
      event             = None,
      triggerEvent      = None,
      eventPayload      = None,
      commitSha         = None,
      prettyRef         = None,
      htmlUrl           = None,
      repository        = None,
      triggerUser       = None,
      isForkPullRequest = None,
      needApproval      = None,
      isRefDeleted      = None,
      approvedBy        = None,
      scheduleId        = None,
      durationNanos     = None,
      created           = None,
      started           = None,
      stopped           = None,
      updated           = None,
    )

  private val FullBody: String =
    """{
      |  "id": 4711,
      |  "index_in_repo": 42,
      |  "title": "release the hounds",
      |  "status": "failure",
      |  "workflow_id": "build.yml",
      |  "event": "push",
      |  "trigger_event": "push",
      |  "event_payload": "{\"ref\":\"refs/heads/main\"}",
      |  "commit_sha": "5f7e2e5c003c066a865ea483e42809fa87d85eae",
      |  "prettyref": "main",
      |  "html_url": "https://codeberg.org/forgejo/forgejo/actions/runs/42",
      |  "repository": {"id": 1, "name": "forgejo", "full_name": "forgejo/forgejo", "owner": {"id": 2, "login": "forgejo"}},
      |  "trigger_user": {"id": 3, "login": "earl-warren"},
      |  "is_fork_pull_request": false,
      |  "need_approval": false,
      |  "is_ref_deleted": false,
      |  "approved_by": 0,
      |  "ScheduleID": 7,
      |  "duration": 90000000000,
      |  "created": "2026-07-30T21:14:15+02:00",
      |  "started": "2026-07-30T21:14:20+02:00",
      |  "stopped": "0001-01-01T00:00:00Z",
      |  "updated": "2026-07-30T21:15:45+02:00"
      |}""".stripMargin

  /** Every optional field explicitly `null` — the shape `docs/HAZARDS.md` §1 says to expect. */
  private val NullBody: String =
    """{
      |  "id": 4711,
      |  "index_in_repo": null,
      |  "title": null,
      |  "status": null,
      |  "workflow_id": null,
      |  "event": null,
      |  "trigger_event": null,
      |  "event_payload": null,
      |  "commit_sha": null,
      |  "prettyref": null,
      |  "html_url": null,
      |  "repository": null,
      |  "trigger_user": null,
      |  "is_fork_pull_request": null,
      |  "need_approval": null,
      |  "is_ref_deleted": null,
      |  "approved_by": null,
      |  "ScheduleID": null,
      |  "duration": null,
      |  "created": null,
      |  "started": null,
      |  "stopped": null,
      |  "updated": null
      |}""".stripMargin

  /** The same run with every optional key simply missing. */
  private val AbsentBody: String = """{"id": 4711}"""
