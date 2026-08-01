package com.worxbend.codeberg4s.notifications

import munit.FunSuite

import java.time.Instant

/** [[NotificationQuery]]'s builders: each one sets exactly the filter it names and leaves the rest alone. */
final class NotificationQuerySuite extends FunSuite:

  private val Since: Instant = Instant.parse("2026-07-01T00:00:00Z")

  test("the empty query carries no filter at all, so the instance's own default applies"):
    assertEquals(NotificationQuery.Empty.includeRead, false)
    assertEquals(NotificationQuery.Empty.statuses, Vector.empty[NotificationStatus])
    assertEquals(NotificationQuery.Empty.subjects, Vector.empty[NotificationSubjectFilter])
    assertEquals(NotificationQuery.Empty.since, None)
    assertEquals(NotificationQuery.Empty.before, None)

  test("includingRead is how a caller asks for read threads, since omitting all means unread and pinned"):
    assertEquals(NotificationQuery.Empty.includingRead.includeRead, true)

  test("unreadOnly puts the flag back, rather than there being a second way to spell false"):
    assertEquals(NotificationQuery.Empty.includingRead.unreadOnly.includeRead, false)

  test("a builder leaves every other filter untouched"):
    val query = NotificationQuery.Empty.withStatuses(Vector(NotificationStatus.Pinned))

    assertEquals(query.statuses, Vector(NotificationStatus.Pinned))
    assertEquals(query.subjects, Vector.empty[NotificationSubjectFilter])
    assertEquals(query.includeRead, false)
    assertEquals(query.since, None)

  test("an empty vector removes a filter rather than asking for nothing"):
    val query = NotificationQuery.Empty
      .withSubjects(Vector(NotificationSubjectFilter.Pull))
      .withSubjects(Vector.empty)

    assertEquals(query.subjects, Vector.empty[NotificationSubjectFilter])

  test("builders compose, and the last call for a filter wins"):
    val query = NotificationQuery.Empty
      .withStatuses(Vector(NotificationStatus.Read))
      .withStatuses(Vector(NotificationStatus.Unread, NotificationStatus.Pinned))
      .includingRead

    assertEquals(query.statuses, Vector(NotificationStatus.Unread, NotificationStatus.Pinned))
    assertEquals(query.includeRead, true)

  test("updatedSince and updatedBefore set the two ends of the window separately"):
    assertEquals(NotificationQuery.Empty.updatedSince(Since).since, Some(Since))
    assertEquals(NotificationQuery.Empty.updatedSince(Since).before, None)
    assertEquals(NotificationQuery.Empty.updatedBefore(Since).before, Some(Since))
    assertEquals(NotificationQuery.Empty.updatedBefore(Since).since, None)
