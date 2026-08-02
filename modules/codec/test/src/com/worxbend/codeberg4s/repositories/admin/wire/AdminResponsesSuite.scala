package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.admin.ActivityOperation
import com.worxbend.codeberg4s.repositories.admin.ForkSyncInfo
import com.worxbend.codeberg4s.repositories.admin.IssuePinsAllowed
import com.worxbend.codeberg4s.repositories.admin.LanguageBreakdown
import com.worxbend.codeberg4s.repositories.admin.PushMirror
import com.worxbend.codeberg4s.repositories.admin.RepositoryActivity
import com.worxbend.codeberg4s.repositories.admin.TopicSummary
import com.worxbend.codeberg4s.repositories.admin.WatchStatus

import munit.FunSuite

/** The response shapes this group declares.
  *
  * '''Every payload below was written by hand from `spec/swagger.v1.json`.''' No golden fixture backs any endpoint in
  * this group — the harvest was anonymous and every one of these needs a token or describes the calling account — so
  * these assert that the DTO matches the '''spec''', not that Forgejo sends exactly this.
  *
  * The recurring shape of the suite is the rule `docs/HAZARDS.md` §1 forces on every DTO here: an absent key, a key
  * whose value is JSON `null`, and a key of the wrong JSON kind must all decode identically. Each model is therefore
  * asserted three times — fully populated, all-null, and empty object — and the last two must agree field for field.
  */
final class AdminResponsesSuite extends FunSuite:

  // --- push mirror ----------------------------------------------------------

  test("a push mirror decodes every property the spec declares"):
    val mirror = decodedMirror(AdminResponsesSuite.PushMirrorBody)

    assertEquals(mirror.remoteName.value, "remote_a1b2c3")
    assertEquals(mirror.remoteAddress, Some("https://example.test/a/b.git"))
    assertEquals(mirror.repoName, Some("b"))
    assertEquals(mirror.branchFilter, Some("main,release/*"))
    assertEquals(mirror.interval, Some("8h0m0s"))
    assertEquals(mirror.syncsOnCommit, true)
    assertEquals(mirror.lastError, Some("dial tcp: i/o timeout"))
    assertEquals(mirror.publicKey, Some("ssh-ed25519 AAAA"))
    assertEquals(mirror.createdAt.map(_.toString), Some("2026-07-30T19:14:15Z"))
    assertEquals(mirror.lastUpdateAt.map(_.toString), Some("2026-08-01T06:00:00Z"))

  test("a push mirror decodes JSON null and an absent key identically"):
    val nulled = decodedMirror(AdminResponsesSuite.PushMirrorNulls)
    val absent = decodedMirror("""{"remote_name": "remote_a1b2c3"}""")

    assertEquals(nulled, absent)

  test("a push mirror with nothing but its name keeps every other field absent"):
    val mirror = decodedMirror("""{"remote_name": "remote_a1b2c3"}""")

    assertEquals(mirror.remoteAddress, None)
    assertEquals(mirror.repoName, None)
    assertEquals(mirror.branchFilter, None)
    assertEquals(mirror.interval, None)
    assertEquals(mirror.lastError, None)
    assertEquals(mirror.publicKey, None)
    assertEquals(mirror.createdAt, None)
    assertEquals(mirror.lastUpdateAt, None)
    assertEquals(mirror.syncsOnCommit, false)

  test("a healthy mirror sends an empty last_error, which is absence rather than an empty message"):
    assertEquals(decodedMirror("""{"remote_name": "remote_x", "last_error": ""}""").isFailing, false)

  test("a push mirror without a remote name fails at that field, because nothing else can address it"):
    assertEquals(failedMirror("""{"remote_address": "https://example.test"}""").map(_.path.render), Some("$.remote_name"))

  test("a push mirror whose remote name would forge a path fails at that field"):
    assertEquals(failedMirror("""{"remote_name": "a/b"}""").map(_.path.render), Some("$.remote_name"))

  // --- watch info -----------------------------------------------------------

  test("a subscription decodes every property the spec declares"):
    val status = decoded[WatchInfoDto](AdminResponsesSuite.WatchBody).toDomain

    assertEquals(status.subscribed, true)
    assertEquals(status.ignored, false)
    assertEquals(status.reason, None)
    assertEquals(status.url, Some("https://forge.test/api/v1/repos/a/b/subscription"))
    assertEquals(status.repositoryUrl, Some("https://forge.test/api/v1/repos/a/b"))
    assertEquals(status.createdAt.map(_.toString), Some("2026-07-30T19:14:15Z"))

  test("a subscription decodes JSON null and an absent key identically"):
    val nulled = decoded[WatchInfoDto](AdminResponsesSuite.WatchNulls).toDomain
    val absent = decoded[WatchInfoDto]("{}").toDomain

    assertEquals(nulled, absent)
    assertEquals(absent, WatchStatus(subscribed = false, ignored = false, None, None, None, None))

  test("the untyped reason decodes as text when the instance sends one, and as absence otherwise"):
    assertEquals(decoded[WatchInfoDto]("""{"reason": "mentioned"}""").toDomain.reason, Some("mentioned"))
    assertEquals(decoded[WatchInfoDto]("""{"reason": {"kind": "mentioned"}}""").toDomain.reason, None)

  // --- fork sync ------------------------------------------------------------

  test("fork sync info decodes every property the spec declares"):
    val info = decoded[SyncForkInfoDto](AdminResponsesSuite.SyncForkBody).toDomain

    assertEquals(info.allowed, true)
    assertEquals(info.commitsBehind, 12L)
    assertEquals(info.baseCommit, Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    assertEquals(info.forkCommit, Some("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))

  test("fork sync info decodes JSON null and an absent key identically, and defaults conservatively"):
    val nulled = decoded[SyncForkInfoDto](AdminResponsesSuite.SyncForkNulls).toDomain
    val absent = decoded[SyncForkInfoDto]("{}").toDomain

    assertEquals(nulled, absent)
    assertEquals(absent, ForkSyncInfo(allowed = false, commitsBehind = 0L, None, None))

  test("an empty base commit is absence, so a fork with no upstream branch is not a decoding failure"):
    assertEquals(decoded[SyncForkInfoDto]("""{"allowed": false, "base_commit": ""}""").toDomain.baseCommit, None)

  // --- pin allowance --------------------------------------------------------

  test("pin allowance decodes both flags"):
    val allowed = decoded[IssuePinsAllowedDto]("""{"issues": true, "pull_requests": false}""").toDomain

    assertEquals(allowed, IssuePinsAllowed(issues = true, pullRequests = false))

  test("pin allowance decodes JSON null and an absent key identically, and denies by default"):
    val nulled = decoded[IssuePinsAllowedDto]("""{"issues": null, "pull_requests": null}""").toDomain
    val absent = decoded[IssuePinsAllowedDto]("{}").toDomain

    assertEquals(nulled, absent)
    assertEquals(absent, IssuePinsAllowed(issues = false, pullRequests = false))

  // --- languages ------------------------------------------------------------

  test("language statistics decode every key the object happens to have"):
    val breakdown = decoded[LanguageStatisticsDto]("""{"Go": 1234567, "Scala": 8901}""").toDomain

    assertEquals(breakdown.bytes, Map("Go" -> 1234567L, "Scala" -> 8901L))
    assertEquals(breakdown.dominant, Some("Go"))

  test("an unanalysed repository answers an empty object, which is a success and not a failure"):
    assertEquals(decoded[LanguageStatisticsDto]("{}").toDomain, LanguageBreakdown.Empty)

  test("a language whose count is null or the wrong kind is dropped, not fatal"):
    val breakdown = decoded[LanguageStatisticsDto]("""{"Go": 10, "Scala": null, "Rust": "lots"}""").toDomain

    assertEquals(breakdown.bytes, Map("Go" -> 10L))

  // --- topics ---------------------------------------------------------------

  test("a topic decodes every property, including the topic_name spelling"):
    val topic = decodedTopic(AdminResponsesSuite.TopicBody)

    assertEquals(topic.id.value, 41L)
    assertEquals(topic.name, "scala")
    assertEquals(topic.repositoryCount, 137L)
    assertEquals(topic.createdAt.map(_.toString), Some("2026-07-30T19:14:15Z"))
    assertEquals(topic.updatedAt.map(_.toString), Some("2026-08-01T06:00:00Z"))

  test("a topic decodes JSON null and an absent key identically for its optional fields"):
    val nulled = decodedTopic("""{"id": 41, "topic_name": "scala", "repo_count": null,
                                 |"created": null, "updated": null}""".stripMargin)
    val absent = decodedTopic("""{"id": 41, "topic_name": "scala"}""")

    assertEquals(nulled, absent)
    assertEquals(absent.repositoryCount, 0L)

  test("a topic without a name fails at that field"):
    val failure = Json.decode[TopicSummaryDto]("""{"id": 41}""").flatMap(_.toDomain).swap.toOption

    assertEquals(failure.map(_.path.render), Some("$.topic_name"))

  test("the topic search envelope reports a bad element at its position inside topics"):
    val envelope = Json.decode[TopicSearchEnvelopeDto]("""{"topics": [{"id": 1, "topic_name": "a"}, {"id": 2}]}""")
    val failure  = envelope.flatMap(e => TopicSummaryDto.toDomainAll(TopicPath, e.entries)).swap.toOption

    assertEquals(failure.map(_.path.render), Some("$.topics[1].topic_name"))

  test("a topic search that matched nothing decodes as an empty envelope, whichever way it says so"):
    assertEquals(decoded[TopicSearchEnvelopeDto]("""{"topics": []}""").entries, Vector.empty[TopicSummaryDto])
    assertEquals(decoded[TopicSearchEnvelopeDto]("""{"topics": null}""").entries, Vector.empty[TopicSummaryDto])
    assertEquals(decoded[TopicSearchEnvelopeDto]("{}").entries, Vector.empty[TopicSummaryDto])

  // --- activity -------------------------------------------------------------

  test("an activity decodes every property, embedding the user, repository and comment models"):
    val activity = decodedActivity(AdminResponsesSuite.ActivityBody)

    assertEquals(activity.id.value, 900L)
    assertEquals(activity.operation, Some(ActivityOperation.CommitRepo))
    assertEquals(activity.refName, Some("refs/heads/main"))
    assertEquals(activity.content, Some("""{"Commits":[]}"""))
    assertEquals(activity.isPrivate, false)
    assertEquals(activity.actor.map(_.login), Some("octocat"))
    assertEquals(activity.repository.map(_.slug.name.value), Some("b"))
    assertEquals(activity.comment.map(_.id.value), Some(77L))
    assertEquals(activity.createdAt.map(_.toString), Some("2026-07-30T19:14:15Z"))

  test("an activity decodes JSON null and an absent key identically"):
    val nulled = decodedActivity(AdminResponsesSuite.ActivityNulls)
    val absent = decodedActivity("""{"id": 900}""")

    assertEquals(nulled, absent)

  test("an activity with nothing but its id keeps every embedded model absent"):
    val activity = decodedActivity("""{"id": 900}""")

    assertEquals(activity.actor, None)
    assertEquals(activity.repository, None)
    assertEquals(activity.comment, None)
    assertEquals(activity.operation, None)
    assertEquals(activity.refName, None)
    assertEquals(activity.content, None)
    assertEquals(activity.createdAt, None)
    assertEquals(activity.isPrivate, false)

  test("an op_type this release does not know is absence, not a failure — one new case cannot cost a page"):
    assertEquals(decodedActivity("""{"id": 1, "op_type": "teleported_repo"}""").operation, None)

  test("an activity without an id fails at that field, because a feed cannot be de-duplicated without it"):
    val failure = Json.decode[ActivityDto]("""{"op_type": "star_repo"}""").flatMap(_.toDomain).swap.toOption

    assertEquals(failure.map(_.path.render), Some("$.id"))

  test("a failure inside the embedded actor is reported at the actor's own path"):
    val failure = Json.decode[ActivityDto]("""{"id": 1, "act_user": {"login": "x"}}""")
      .flatMap(_.toDomain)
      .swap
      .toOption

    assertEquals(failure.map(_.path.render), Some("$.act_user.id"))

  test("an activity array reports a bad element at its own index"):
    val failure = Json.decode[Vector[ActivityDto]]("""[{"id": 1}, {"op_type": "star_repo"}]""")
      .flatMap(dtos => ActivityDto.toDomainAll(com.worxbend.codeberg4s.JsonPath.Root, dtos))
      .swap
      .toOption

    assertEquals(failure.map(_.path.render), Some("$[1].id"))

  // --- batch write response -------------------------------------------------

  test("a batch write response decodes its commit, its entries and its verification"):
    val changed = decoded[FilesResponseDto](AdminResponsesSuite.FilesBody).toDomain

    assertEquals(changed.map(_.commit.flatMap(_.message)), Right(Some("write two files")))
    assertEquals(changed.map(_.files.map(_.meta.path.value)), Right(Vector("a.txt", "b.txt")))
    assertEquals(changed.map(_.verification.map(_.isVerified)), Right(Some(true)))

  test("a batch of deletes answers an empty files array, which is a success"):
    val changed = decoded[FilesResponseDto]("""{"commit": {"sha": "aaaaaaa"}, "files": []}""").toDomain

    assertEquals(changed.map(_.files.isEmpty), Right(true))

  test("a batch write response decodes JSON null and an absent key identically"):
    val nulled = decoded[FilesResponseDto]("""{"commit": null, "files": null, "verification": null}""").toDomain
    val absent = decoded[FilesResponseDto]("{}").toDomain

    assertEquals(nulled, absent)
    assertEquals(absent.map(_.commit), Right(None))

  test("a bad entry in a batch write response is reported at its own position inside files"):
    val body    = """{"files": [{"name": "a", "path": "a.txt", "sha": "aaaa", "type": "file"}, {"name": "b"}]}"""
    val failure = decoded[FilesResponseDto](body).toDomain.swap.toOption

    assertEquals(failure.map(_.path.render), Some("$.files[1].path"))

  private val TopicPath: com.worxbend.codeberg4s.JsonPath =
    com.worxbend.codeberg4s.JsonPath.Root.field(TopicSearchEnvelopeDto.EntriesKey)

  private def decodedMirror(body: String): PushMirror =
    Json.decode[PushMirrorDto](body).flatMap(_.toDomain) match
      case Right(mirror) => mirror
      case Left(failure) => fail(s"expected a push mirror, got ${failure.path.render}: ${failure.message}")

  private def failedMirror(body: String): Option[DecodeFailure] =
    Json.decode[PushMirrorDto](body).flatMap(_.toDomain).swap.toOption

  private def decodedTopic(body: String): TopicSummary =
    Json.decode[TopicSummaryDto](body).flatMap(_.toDomain) match
      case Right(topic)  => topic
      case Left(failure) => fail(s"expected a topic, got ${failure.path.render}: ${failure.message}")

  private def decodedActivity(body: String): RepositoryActivity =
    Json.decode[ActivityDto](body).flatMap(_.toDomain) match
      case Right(activity) => activity
      case Left(failure)   => fail(s"expected an activity, got ${failure.path.render}: ${failure.message}")

  private def decoded[A: upickle.default.Reader](body: String): A =
    Json.decode[A](body) match
      case Right(value)  => value
      case Left(failure) => fail(s"expected a value, got ${failure.path.render}: ${failure.message}")

/** The payloads this suite decodes, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no endpoint in this group has a golden capture.
  */
object AdminResponsesSuite:

  private val PushMirrorBody: String =
    """{
      |  "remote_name": "remote_a1b2c3",
      |  "remote_address": "https://example.test/a/b.git",
      |  "repo_name": "b",
      |  "branch_filter": "main,release/*",
      |  "interval": "8h0m0s",
      |  "sync_on_commit": true,
      |  "last_error": "dial tcp: i/o timeout",
      |  "public_key": "ssh-ed25519 AAAA",
      |  "created": "2026-07-30T21:14:15+02:00",
      |  "last_update": "2026-08-01T08:00:00+02:00"
      |}""".stripMargin

  private val PushMirrorNulls: String =
    """{
      |  "remote_name": "remote_a1b2c3",
      |  "remote_address": null,
      |  "repo_name": null,
      |  "branch_filter": null,
      |  "interval": null,
      |  "sync_on_commit": null,
      |  "last_error": null,
      |  "public_key": null,
      |  "created": null,
      |  "last_update": null
      |}""".stripMargin

  private val WatchBody: String =
    """{
      |  "subscribed": true,
      |  "ignored": false,
      |  "reason": null,
      |  "url": "https://forge.test/api/v1/repos/a/b/subscription",
      |  "repository_url": "https://forge.test/api/v1/repos/a/b",
      |  "created_at": "2026-07-30T21:14:15+02:00"
      |}""".stripMargin

  private val WatchNulls: String =
    """{
      |  "subscribed": null,
      |  "ignored": null,
      |  "reason": null,
      |  "url": null,
      |  "repository_url": null,
      |  "created_at": null
      |}""".stripMargin

  private val SyncForkBody: String =
    """{
      |  "allowed": true,
      |  "commits_behind": 12,
      |  "base_commit": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      |  "fork_commit": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      |}""".stripMargin

  private val SyncForkNulls: String =
    """{"allowed": null, "commits_behind": null, "base_commit": null, "fork_commit": null}"""

  private val TopicBody: String =
    """{
      |  "id": 41,
      |  "topic_name": "scala",
      |  "repo_count": 137,
      |  "created": "2026-07-30T21:14:15+02:00",
      |  "updated": "2026-08-01T08:00:00+02:00"
      |}""".stripMargin

  private val ActivityBody: String =
    """{
      |  "id": 900,
      |  "act_user": {"id": 3, "login": "octocat"},
      |  "act_user_id": 3,
      |  "op_type": "commit_repo",
      |  "ref_name": "refs/heads/main",
      |  "content": "{\"Commits\":[]}",
      |  "repo": {"id": 12, "name": "b", "owner": {"id": 3, "login": "octocat"}},
      |  "repo_id": 12,
      |  "comment": {"id": 77},
      |  "comment_id": 77,
      |  "user_id": 3,
      |  "is_private": false,
      |  "created": "2026-07-30T21:14:15+02:00"
      |}""".stripMargin

  private val ActivityNulls: String =
    """{
      |  "id": 900,
      |  "act_user": null,
      |  "act_user_id": null,
      |  "op_type": null,
      |  "ref_name": null,
      |  "content": null,
      |  "repo": null,
      |  "repo_id": null,
      |  "comment": null,
      |  "comment_id": null,
      |  "user_id": null,
      |  "is_private": null,
      |  "created": null
      |}""".stripMargin

  private val FilesBody: String =
    """{
      |  "commit": {"sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "message": "write two files"},
      |  "files": [
      |    {"name": "a.txt", "path": "a.txt", "sha": "1111111111111111111111111111111111111111", "type": "file"},
      |    {"name": "b.txt", "path": "b.txt", "sha": "2222222222222222222222222222222222222222", "type": "file"}
      |  ],
      |  "verification": {"verified": true}
      |}""".stripMargin
