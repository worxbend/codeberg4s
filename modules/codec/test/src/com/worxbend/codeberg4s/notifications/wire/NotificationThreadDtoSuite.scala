package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{GoldenFixtures, Json, WireModel}
import com.worxbend.codeberg4s.notifications.NotificationSubjectType
import com.worxbend.codeberg4s.notifications.NotificationThread

import munit.FunSuite

import java.time.Instant

/** Decodes the one notification fixture there is — and says out loud what that does and does not prove.
  *
  * `golden/notification/list-synthetic.json` is hand-authored, and `golden/MANIFEST.md` marks it `synthetic`: the
  * endpoint answers `401` without a token, so the anonymous harvest never reached it. These assertions therefore prove
  * that the reader agrees with `spec/swagger.v1.json`, that the two nested models are wired up, and that the leniency
  * rules behave — they prove nothing about what a live instance sends. The one genuinely captured thing in the file is
  * the embedded `repository` object, copied from `repository/repo-single-community.json`, and it is asserted on
  * deliberately for that reason.
  *
  * The rest of the suite works on hand-written bodies chosen to exercise a seam: an absent discriminator, a null
  * subject, a bad element position.
  */
final class NotificationThreadDtoSuite extends FunSuite with GoldenFixtures:

  private def decodeList(fixture: String): Vector[NotificationThreadDto] =
    Json.decode[Vector[NotificationThreadDto]](golden(fixture)) match
      case Right(dtos)   => dtos
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domain(body: String): Vector[NotificationThread] =
    Json.decode[Vector[NotificationThreadDto]](body).flatMap(dtos =>
      WireModel.all(JsonPath.Root, dtos)
    ) match
      case Right(threads) => threads
      case Left(failure)  => fail(s"body did not convert: ${failure.path.render} ${failure.message}")

  test("the synthetic listing decodes as two threads, field for field"):
    val dtos = decodeList("notification/list-synthetic.json")

    assertEquals(dtos.length, 2)
    assertEquals(dtos.head.id, Some(1L))
    assertEquals(dtos.head.unread, Some(true))
    assertEquals(dtos.head.pinned, Some(false))
    assertEquals(dtos.head.updatedAt, Some("2026-08-02T12:00:00+02:00"))
    assertEquals(dtos.head.url, Some("https://codeberg.org/api/v1/notifications/threads/1"))

  test("the second thread is the pinned, already-read one"):
    val second = decodeList("notification/list-synthetic.json")(1)

    assertEquals(second.id, Some(2L))
    assertEquals(second.unread, Some(false))
    assertEquals(second.pinned, Some(true))

  test("the embedded subject decodes, discriminator included"):
    val subject = decodeList("notification/list-synthetic.json").head.subject

    assertEquals(subject.flatMap(_.subjectType), Some("Issue"))
    assertEquals(subject.flatMap(_.title), Some("Synthetic issue notification subject"))
    assertEquals(subject.flatMap(_.state), Some("open"))
    assertEquals(subject.flatMap(_.url), Some("https://codeberg.org/api/v1/repos/codeberg/Community/issues/2966"))
    assertEquals(subject.flatMap(_.htmlUrl), Some("https://codeberg.org/codeberg/Community/issues/2966"))

  test("a comment URL Forgejo sends as an empty string decodes as absent, not as an empty link"):
    val subject = decodeList("notification/list-synthetic.json")(1).subject

    assertEquals(subject.flatMap(_.latestCommentUrl), None)
    assertEquals(subject.flatMap(_.latestCommentHtmlUrl), None)

  test("the embedded repository is the real capture, and it decodes through the repository group's own DTO"):
    val repository = decodeList("notification/list-synthetic.json").head.repository

    assertEquals(repository.flatMap(_.id), Some(1L))
    assertEquals(repository.flatMap(_.fullName), Some("Codeberg/Community"))
    assertEquals(repository.flatMap(_.owner).flatMap(_.login), Some("Codeberg"))
    assertEquals(repository.map(_.topics), Some(Vector("codeberg", "community")))

  test("the synthetic listing converts to the domain"):
    val threads = domain(golden("notification/list-synthetic.json"))

    assertEquals(threads.length, 2)
    assertEquals(threads.head.id.value, 1L)
    assertEquals(threads.head.isUnread, true)
    assertEquals(threads.head.isPinned, false)
    assertEquals(threads.head.subject.map(_.subjectType), Some(NotificationSubjectType.Issue))
    assertEquals(threads.head.updatedAt, Some(Instant.parse("2026-08-02T10:00:00Z")))
    assertEquals(threads.head.repository.map(_.slug.value), Some("Codeberg/Community"))

  test("a pull-request subject keeps the merged state the issue lifecycle could not represent"):
    val second = domain(golden("notification/list-synthetic.json"))(1)

    assertEquals(second.subject.map(_.subjectType), Some(NotificationSubjectType.Pull))
    assertEquals(second.subject.flatMap(_.state), Some("merged"))
    assertEquals(second.isUnread, false)
    assertEquals(second.isPinned, true)

  test("an unrecognised discriminator survives as data rather than failing the page"):
    val threads = domain("""[{"id":9,"subject":{"type":"Discussion","title":"t"}}]""")

    assertEquals(threads.head.subject.map(_.subjectType), Some(NotificationSubjectType.Other("Discussion")))

  test("a thread with no subject and no repository is a thread, not a failure"):
    val threads = domain("""[{"id":9,"subject":null,"repository":null}]""")

    assertEquals(threads.head.subject, None)
    assertEquals(threads.head.repository, None)
    assertEquals(threads.head.isUnread, false)
    assertEquals(threads.head.isPinned, false)
    assertEquals(threads.head.url, None)
    assertEquals(threads.head.updatedAt, None)

  test("an absent id is the one thing that fails, and it says so at its own path"):
    Json.decode[NotificationThreadDto]("""{"unread":true}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.id")
      case Right(thread) => fail(s"a thread without an id should not convert, got $thread")

  test("a zero id is rejected by the identifier rather than sent as /threads/0"):
    Json.decode[NotificationThreadDto]("""{"id":0}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.message, "must be at least 1")
      case Right(thread) => fail(s"a thread with id 0 should not convert, got $thread")

  test("a subject without a discriminator fails at the nested path, not at the thread's"):
    Json.decode[NotificationThreadDto]("""{"id":9,"subject":{"title":"t"}}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.subject.type")
      case Right(thread) => fail(s"a subject without a type should not convert, got $thread")

  test("a bad element of a list reports its position"):
    Json
      .decode[Vector[NotificationThreadDto]]("""[{"id":1},{"subject":null}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos)) match
      case Left(failure)  => assertEquals(failure.path.render, "$[1].id")
      case Right(threads) => fail(s"a list with a bad element should not convert, got $threads")

  test("Forgejo's zero-time sentinel is absence, not a timestamp in the year one"):
    val threads = domain("""[{"id":9,"updated_at":"0001-01-01T00:00:00Z"}]""")

    assertEquals(threads.head.updatedAt, None)

  test("a field of the wrong JSON kind is absence, so one odd key does not cost the whole thread"):
    val threads = domain("""[{"id":9,"unread":"yes","url":42}]""")

    assertEquals(threads.head.isUnread, false)
    assertEquals(threads.head.url, None)
