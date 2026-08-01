package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.notifications.UnreadCount

/** The body of `GET /notifications/new`, verbatim.
  *
  * `definitions.NotificationCount` declares one key, `new`, typed `integer/int64`. Like everything else in this group
  * it is '''spec-derived and unverified''': the endpoint needs a token, so `golden/` contains no capture of it and not
  * even a synthetic fixture — the one synthetic file covers the listing, not the count.
  *
  * @param unread
  *   the `new` key, renamed because `new` is a Scala keyword. The wire spelling appears once, in the reader, per rule 4
  *   of [[com.worxbend.codeberg4s.codec.WireConventions]]
  */
final case class NotificationCountDto(unread: Option[Long]):

  /** Converts to the domain, reporting the path of the offending field relative to `at`.
    *
    * Fails with [[com.worxbend.codeberg4s.core.DecodeFailure]] when `new` is absent, `null`, not a number, or negative:
    * a count endpoint that did not answer with a count has not answered the question that was asked, and a negative
    * count is a value Forgejo has no reason to produce.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, UnreadCount] =
    Wire.validated(at, "new", unread)(UnreadCount.from)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, UnreadCount] =
    toDomainAt(JsonPath.Root)

object NotificationCountDto:

  /** Reads a `/notifications/new` body. Absent, `null` and non-numeric all decode to `None`; the document must still be
    * a JSON object, which upickle enforces.
    */
  given upickle.default.Reader[NotificationCountDto] =
    JsonFields.reader(fields => NotificationCountDto(unread = fields.number("new")))
