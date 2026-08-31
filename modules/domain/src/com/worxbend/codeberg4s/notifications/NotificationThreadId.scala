package com.worxbend.codeberg4s.notifications

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of a [[NotificationThread]] — the `{id}` of `/notifications/threads/{id}`.
  *
  * A thread is read and marked read by this id and by nothing else: unlike an issue, a notification has no
  * per-repository number and no other addressable name. Rejecting non-positive values here means `/threads/0` is a
  * caller bug caught before a request is built, rather than a `404` that reads like a thread somebody else already
  * deleted.
  *
  * ==Evidence==
  *
  * `id` is declared `integer/int64` by `spec/swagger.v1.json`'s `NotificationThread`, and appears as a small positive
  * integer in `golden/notification/list-synthetic.json`. That fixture is '''hand-authored''', not captured — see
  * [[NotificationThread]] — so the lower bound below is the spec's word plus the shape every other Forgejo row id has,
  * not a measurement.
  */
opaque type NotificationThreadId = Long

object NotificationThreadId:

  /** Parses a notification thread id.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the id, or a [[ValidationError]] on the `"notificationThreadId"` field
    */
  def from(value: Long): Either[ValidationError, NotificationThreadId] =
    PositiveId.from("notificationThreadId", value)

  extension (id: NotificationThreadId)

    /** The id as a `Long`. */
    def value: Long = id
