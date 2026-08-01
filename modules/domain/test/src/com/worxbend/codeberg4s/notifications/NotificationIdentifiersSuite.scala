package com.worxbend.codeberg4s.notifications

import munit.FunSuite

/** The two numeric value types this group owns.
  *
  * They are tested together because each is a handful of lines and neither has behaviour beyond its bound; what a
  * caller branches on is the field name a rejection reports, and that is asserted for both.
  */
final class NotificationIdentifiersSuite extends FunSuite:

  test("a thread id is accepted from one upwards"):
    assertEquals(NotificationThreadId.from(1L).map(_.value), Right(1L))
    assertEquals(NotificationThreadId.from(4821L).map(_.value), Right(4821L))

  test("thread id zero is rejected rather than sent as /threads/0"):
    assertEquals(NotificationThreadId.from(0L).left.map(_.field), Left("notificationThreadId"))

  test("a negative thread id is rejected"):
    assertEquals(NotificationThreadId.from(-1L).left.map(_.field), Left("notificationThreadId"))

  test("the thread id rejection message says what would have been acceptable"):
    assertEquals(NotificationThreadId.from(0L).left.map(_.message), Left("must be at least 1"))

  test("an unread count accepts zero, because an empty inbox is a perfectly good answer"):
    assertEquals(UnreadCount.from(0L).map(_.value), Right(0L))

  test("an unread count accepts a positive number"):
    assertEquals(UnreadCount.from(17L).map(_.value), Right(17L))

  test("a negative unread count is rejected, since Forgejo has no reason to send one"):
    assertEquals(UnreadCount.from(-1L).left.map(_.field), Left("unreadCount"))
    assertEquals(UnreadCount.from(-1L).left.map(_.message), Left("must be at least 0"))

  test("hasUnread answers the question Forgejo's own endpoint summary claims to answer"):
    assertEquals(UnreadCount.Zero.hasUnread, false)
    assertEquals(UnreadCount.from(1L).map(_.hasUnread), Right(true))

  test("Zero is the count it says it is"):
    assertEquals(UnreadCount.Zero.value, 0L)
