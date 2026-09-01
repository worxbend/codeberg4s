package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.social.BlockedUser
import com.worxbend.codeberg4s.users.social.HeatmapEntry
import com.worxbend.codeberg4s.users.social.StopWatch

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.time.Instant

/** The three small models this group adds: a block entry, a running stopwatch and a heatmap bucket.
  *
  * '''None of the three has a golden fixture behind it.''' `/user/list_blocked` and `/user/stopwatches` need a token,
  * and `/users/{u}/heatmap` was not among the 61 anonymous captures, so every body below is hand-authored from the
  * pinned spec's `BlockedUser`, `StopWatch` and `UserHeatmapData` definitions. They are shape-only evidence — never
  * evidence of optionality, which `docs/HAZARDS.md` §1 measures the spec does not carry.
  */
final class SocialDtoSuite extends FunSuite:

  // --- blocked users --------------------------------------------------------

  test("a block entry decodes and converts field for field"):
    val entry = blocked("""{"block_id": 99, "created_at": "2026-07-30T21:14:15+02:00"}""")

    assertEquals(entry.blockId.value, 99L)
    assertEquals(entry.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))

  test("an explicit null and an absent timestamp decode identically on a block entry"):
    val explicitNull = decodeBlocked("""{"block_id": 99, "created_at": null}""")
    val absent       = decodeBlocked("""{"block_id": 99}""")

    assertEquals(explicitNull.created, absent.created)
    assertEquals(explicitNull.created, None)

  test("a block entry without a block_id fails, because nothing else distinguishes two entries"):
    assertEquals(blockedFailure("""{"created_at": "2026-07-30T21:14:15+02:00"}""").path.render, "$.block_id")
    assertEquals(blockedFailure("""{"block_id": 0}""").path.render, "$.block_id")

  test("a bad block entry names its position in the array"):
    val outcome = Json
      .decode[Vector[BlockedUserDto]]("""[{"block_id": 1}, {"created_at": null}]""")
      .flatMap(WireModel.all(JsonPath.Root, _))

    outcome match
      case Left(problem) => assertEquals(problem.path.render, "$[1].block_id")
      case Right(items)  => fail(s"expected the second element to fail, got $items")

  // Ported from OrganizationAdminDtoSuite when `/orgs/{org}/list_blocked` and
  // `/user/list_blocked` were collapsed onto this one DTO: the org copy tested
  // these three and this one did not, so they moved rather than being deleted.

  test("the Go zero-time sentinel is absence, not a timestamp in the year one"):
    assertEquals(blocked("""{"block_id": 99, "created_at": "0001-01-01T00:00:00Z"}""").createdAt, None)

  test("a non-positive block_id is refused during conversion, and the failure explains the bound"):
    val problem = blockedFailure("""{"block_id": 0}""")

    assertEquals(problem.path.render, "$.block_id")
    assert(problem.message.contains("at least 1"), s"the failure did not explain the bound: ${problem.message}")

  test("an empty block listing is an empty vector, not a failure"):
    val outcome = Json
      .decode[Vector[BlockedUserDto]]("[]")
      .flatMap(WireModel.all(JsonPath.Root, _))

    assertEquals(outcome, Right(Vector.empty[BlockedUser]))

  // --- stopwatches ----------------------------------------------------------

  test("a stopwatch decodes and converts field for field"):
    val watch = stopWatch(SocialDtoSuite.FullStopWatch)

    assertEquals(watch.issueIndex, 42L)
    assertEquals(watch.issueTitle, Some("the timer runs"))
    assertEquals(watch.repository.map(_.value), Some("forgejo/forgejo"))
    assertEquals(watch.elapsed, 3723.seconds)
    assertEquals(watch.durationText, Some("1h2m3s"))
    assertEquals(watch.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))

  test("an explicit null and an absent key decode identically on a stopwatch"):
    val explicitNulls = decodeStopWatch(SocialDtoSuite.NulledStopWatch)
    val absent        = decodeStopWatch("""{"issue_index": 42}""")

    assertEquals(explicitNulls.seconds, absent.seconds)
    assertEquals(explicitNulls.duration, absent.duration)
    assertEquals(explicitNulls.repoName, absent.repoName)
    assertEquals(explicitNulls.created, absent.created)

  test("a stopwatch with no seconds reads as a timer just started, not as a failure"):
    assertEquals(stopWatch("""{"issue_index": 42}""").elapsed, 0.seconds)

  test("a stopwatch that does not say which issue it is on fails, because it cannot be stopped"):
    assertEquals(stopWatchFailure("""{"seconds": 60}""").path.render, "$.issue_index")

  test("a repository half this client will not carry costs the slug, not the entry"):
    val watch = stopWatch("""{"issue_index": 42, "repo_owner_name": "forgejo", "repo_name": "a/b"}""")

    assertEquals(watch.issueIndex, 42L)
    assertEquals(watch.repository, None)

  // --- heatmap --------------------------------------------------------------

  test("a heatmap bucket turns epoch seconds into an instant at the boundary"):
    val bucket = heatmap("""{"timestamp": 1785000000, "contributions": 7}""")

    assertEquals(bucket.at, Instant.ofEpochSecond(1785000000L))
    assertEquals(bucket.contributions, 7L)

  test("a heatmap bucket's number is never confused with the RFC-3339 strings the rest of the API sends"):
    assertEquals(decodeHeatmap("""{"timestamp": 0, "contributions": 1}""").timestamp, Some(0L))
    assertEquals(heatmap("""{"timestamp": 0, "contributions": 1}""").at, Instant.EPOCH)

  test("an explicit null and an absent key decode identically on a heatmap bucket"):
    val explicitNulls = decodeHeatmap("""{"timestamp": null, "contributions": null}""")
    val absent        = decodeHeatmap("{}")

    assertEquals(explicitNulls.timestamp, absent.timestamp)
    assertEquals(explicitNulls.contributions, absent.contributions)

  test("a heatmap bucket missing either of its two fields fails at that field"):
    assertEquals(heatmapFailure("""{"contributions": 7}""").path.render, "$.timestamp")
    assertEquals(heatmapFailure("""{"timestamp": 1785000000}""").path.render, "$.contributions")

  test("a bad heatmap bucket names its position in the array"):
    val outcome = Json
      .decode[Vector[HeatmapEntryDto]]("""[{"timestamp": 1, "contributions": 1}, {"timestamp": 2}]""")
      .flatMap(WireModel.all(JsonPath.Root, _))

    outcome match
      case Left(problem)  => assertEquals(problem.path.render, "$[1].contributions")
      case Right(buckets) => fail(s"expected the second element to fail, got $buckets")

  // --- harness --------------------------------------------------------------

  private def decodeBlocked(body: String): BlockedUserDto =
    decoded(Json.decode[BlockedUserDto](body))

  private def blocked(body: String): BlockedUser =
    converted(decodeBlocked(body).toDomain)

  private def blockedFailure(body: String): DecodeFailure =
    rejected(decodeBlocked(body).toDomain)

  private def decodeStopWatch(body: String): StopWatchDto =
    decoded(Json.decode[StopWatchDto](body))

  private def stopWatch(body: String): StopWatch =
    converted(decodeStopWatch(body).toDomain)

  private def stopWatchFailure(body: String): DecodeFailure =
    rejected(decodeStopWatch(body).toDomain)

  private def decodeHeatmap(body: String): HeatmapEntryDto =
    decoded(Json.decode[HeatmapEntryDto](body))

  private def heatmap(body: String): HeatmapEntry =
    converted(decodeHeatmap(body).toDomain)

  private def heatmapFailure(body: String): DecodeFailure =
    rejected(decodeHeatmap(body).toDomain)

  private def decoded[A](result: Either[DecodeFailure, A]): A =
    result match
      case Right(dto)    => dto
      case Left(problem) => fail(s"the body did not decode: ${problem.path.render} ${problem.message}")

  private def converted[A](result: Either[DecodeFailure, A]): A =
    result match
      case Right(value)  => value
      case Left(problem) => fail(s"the payload did not convert: ${problem.path.render} ${problem.message}")

  private def rejected[A](result: Either[DecodeFailure, A]): DecodeFailure =
    result match
      case Left(problem) => problem
      case Right(value)  => fail(s"expected the conversion to fail, got $value")

/** The bodies this suite decodes, hand-authored from the pinned spec; see the class note. */
object SocialDtoSuite:

  private val FullStopWatch: String =
    """{
      |  "created": "2026-07-30T21:14:15+02:00",
      |  "duration": "1h2m3s",
      |  "issue_index": 42,
      |  "issue_title": "the timer runs",
      |  "repo_name": "forgejo",
      |  "repo_owner_name": "forgejo",
      |  "seconds": 3723,
      |  "unmodelled_key": "ignored"
      |}""".stripMargin

  private val NulledStopWatch: String =
    """{
      |  "created": null,
      |  "duration": null,
      |  "issue_index": 42,
      |  "issue_title": null,
      |  "repo_name": null,
      |  "repo_owner_name": null,
      |  "seconds": null
      |}""".stripMargin
