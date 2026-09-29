package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.notifications.{NotificationQuery, NotificationStatus}

import java.time.Instant

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the same reason the DTOs do: `all`, `status-types`,
  * `subject-type`, `since`, `before`, `last_read_at` and `to-status` are wire spellings, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. It also means the
  * shape of a request can be asserted on directly, without a stub backend.
  *
  * '''Only parameters the caller set are emitted.''' An absent filter is not the same request as an empty one, and
  * Forgejo's defaults for this group are documented in prose only ("Defaults to unread & pinned"), so nothing here has
  * a default to fall back on. `all=false` in particular is never sent: it is the instance's own default, and sending it
  * would assert a behaviour that could not be probed, since these endpoints answer `401` without a token.
  *
  * '''Two parameters repeat.''' `status-types` and `subject-type` are declared `collectionFormat: multi`, so each
  * selected value becomes its own `key=value` pair rather than a comma-joined list — which is why
  * [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list of pairs and not a map.
  *
  * Parameter order is fixed rather than incidental. Forgejo does not care, but a stable order makes a recorded request
  * comparable between runs.
  */
private[codeberg4s] object NotificationQueries:

  /** The filters of both notification listings, in the order the spec declares them.
    *
    * `since` and `before` are rendered by [[com.worxbend.codeberg4s.codec.Timestamps.render]] in the RFC-3339 form Go
    * parses — reused rather than copied, because a second timestamp renderer that drifted from the first would show up
    * as a `422` carrying a raw Go parse error, exactly as `docs/HAZARDS.md` §4 captured for `?since=notadate`.
    */
  def notifications(query: NotificationQuery): List[(String, String)] =
    List(
      Option.when(query.includeRead)("all" -> "true").toList,
      query.statuses.map(status => "status-types" -> status.wireValue).toList,
      query.subjects.map(subject => "subject-type" -> subject.wireValue).toList,
      query.since.map(moment => "since" -> Timestamps.render(moment)).toList,
      query.before.map(moment => "before" -> Timestamps.render(moment)).toList,
    ).flatten

  /** The `last_read_at` cursor and `to-status` of the two mark-read `PUT`s — `/notifications` and
    * `/repos/{owner}/{repo}/notifications`.
    *
    * Both routes declare the same two parameters under the same spellings, which is why one renderer serves both. The
    * cursor goes through [[com.worxbend.codeberg4s.codec.Timestamps.render]] for the reason [[notifications]] gives;
    * the status is the [[com.worxbend.codeberg4s.notifications.NotificationStatus]] wire value, the same vocabulary the
    * `status-types` filter uses. `all` and `status-types`, which the routes also accept, are deliberately not rendered
    * — see `NotificationApi.markAllRead`.
    */
  def markRead(lastReadAt: Option[Instant], toStatus: Option[NotificationStatus]): List[(String, String)] =
    lastReadAt.map(moment => "last_read_at" -> Timestamps.render(moment)).toList ++
      NotificationQueries.toStatus(toStatus)

  /** The `to-status` of the thread `PATCH` — `/notifications/threads/{id}`.
    *
    * The spec declares it as a free string defaulting to `read`; the values Forgejo accepts are its own
    * `NotificationStatus` vocabulary, so the domain enum stands in for the string. Absent, nothing is sent and the
    * instance's default applies.
    */
  def toStatus(status: Option[NotificationStatus]): List[(String, String)] =
    status.map(wanted => "to-status" -> wanted.wireValue).toList
