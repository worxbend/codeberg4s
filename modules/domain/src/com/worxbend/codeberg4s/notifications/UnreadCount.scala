package com.worxbend.codeberg4s.notifications

import com.worxbend.codeberg4s.ValidationError

/** How many notification threads are waiting — the whole payload of `GET /notifications/new`.
  *
  * A named type rather than a bare `Long` because the endpoint's own name lies about what it returns: Forgejo calls the
  * operation `notifyNewAvailable` and summarises it as "Check if unread notifications exist", but the body is
  * `NotificationCount`, whose single key is a count. A caller that reads the summary and expects a Boolean gets a
  * number; a caller that reads the schema and expects a number gets one. This type takes the schema's side and says so
  * in its name, and [[hasUnread]] serves the other reading without anybody having to compare against zero at a call
  * site.
  *
  * ==Evidence==
  *
  * Spec-derived and '''unverified'''. `GET /notifications/new` needs a token, so `golden/MANIFEST.md` has no capture of
  * it — the count's existence, its key (`new`) and its `int64` type all come from `spec/swagger.v1.json` alone.
  */
opaque type UnreadCount = Long

object UnreadCount:

  private val MinValue: Long = 0L

  /** No unread threads. */
  val Zero: UnreadCount = MinValue

  /** Parses an unread count.
    *
    * Rejects a negative value. Forgejo has no reason to send one, which is exactly why receiving one should be a
    * decoding failure naming the field rather than a negative number travelling into a caller's dashboard.
    *
    * @return
    *   the count, or a [[ValidationError]] on the `"unreadCount"` field
    */
  def from(value: Long): Either[ValidationError, UnreadCount] =
    if value < MinValue then Left(ValidationError("unreadCount", s"must be at least $MinValue"))
    else Right(value)

  extension (count: UnreadCount)

    /** The count as a `Long`. */
    def value: Long = count

    /** Whether anything is waiting at all — the question Forgejo's own summary claims the endpoint answers. */
    def hasUnread: Boolean = count > MinValue
