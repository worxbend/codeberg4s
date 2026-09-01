package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.notifications.NotificationQuery
import com.worxbend.codeberg4s.notifications.NotificationStatus
import com.worxbend.codeberg4s.notifications.NotificationSubjectFilter

import munit.FunSuite

import java.time.Instant

/** The query strings the notification listings send, asserted without a backend.
  *
  * The two properties that matter are that an unset filter contributes nothing, and that the two `collectionFormat:
  * multi` parameters repeat rather than joining with commas — a comma-joined `status-types` would be one unrecognised
  * value, not two recognised ones.
  */
final class NotificationQueriesSuite extends FunSuite:

  private val Since: Instant = Instant.parse("2026-07-01T00:00:00Z")

  test("an empty query sends nothing at all, leaving the instance's default in force"):
    assertEquals(NotificationQueries.notifications(NotificationQuery.Empty), Nil)

  test("all=false is never sent, because it only restates Forgejo's own default"):
    assertEquals(NotificationQueries.notifications(NotificationQuery.Empty.unreadOnly), Nil)

  test("includingRead is the only thing that produces an all parameter"):
    assertEquals(
      NotificationQueries.notifications(NotificationQuery.Empty.includingRead),
      List("all" -> "true"),
    )

  test("status-types repeats once per value rather than joining with commas"):
    val query = NotificationQuery.Empty.withStatuses(
      Vector(NotificationStatus.Unread, NotificationStatus.Pinned)
    )

    assertEquals(
      NotificationQueries.notifications(query),
      List("status-types" -> "unread", "status-types" -> "pinned"),
    )

  test("subject-type repeats the same way, in the lowercase the spec's enum declares"):
    val query = NotificationQuery.Empty.withSubjects(
      Vector(NotificationSubjectFilter.Issue, NotificationSubjectFilter.Repository)
    )

    assertEquals(
      NotificationQueries.notifications(query),
      List("subject-type" -> "issue", "subject-type" -> "repository"),
    )

  test("timestamps are rendered in the RFC-3339 form Go parses, second precision and a Z offset"):
    val query = NotificationQuery.Empty.updatedSince(Since).updatedBefore(Since.plusSeconds(3600L))

    assertEquals(
      NotificationQueries.notifications(query),
      List("since" -> "2026-07-01T00:00:00Z", "before" -> "2026-07-01T01:00:00Z"),
    )

  test("the parameters come out in the order the spec declares them, so a recorded request is comparable"):
    val query = NotificationQuery.Empty.includingRead
      .withStatuses(Vector(NotificationStatus.Read))
      .withSubjects(Vector(NotificationSubjectFilter.Pull))
      .updatedSince(Since)

    assertEquals(
      NotificationQueries.notifications(query),
      List(
        "all"          -> "true",
        "status-types" -> "read",
        "subject-type" -> "pull",
        "since"        -> "2026-07-01T00:00:00Z",
      ),
    )
