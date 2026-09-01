package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.users.social.{ActivityFeedQuery, TrackedTimeWindow}

import java.time.format.DateTimeFormatter

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API classes for the reason
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: `only-performed-by` is a wire spelling — hyphenated,
  * unlike every other parameter in this API — and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a
  * wire spelling is written exactly once. It also means the shape of a request can be asserted on directly, without a
  * stub backend.
  *
  * '''Only parameters the caller set are emitted.''' The paging window is not rendered here at all: it comes from
  * [[com.worxbend.codeberg4s.codec.PagingQuery]], which every paged endpoint calls directly.
  */
private[codeberg4s] object SocialQueries:

  /** The wire key restricting an activity feed to the account's own actions. Hyphenated, not underscored. */
  val OnlyPerformedByKey: String = "only-performed-by"

  /** The wire key restricting an activity feed to one calendar day. */
  val DateKey: String = "date"

  /** The wire key of the lower bound of a tracked-time window. */
  val SinceKey: String = "since"

  /** The wire key of the upper bound of a tracked-time window. */
  val BeforeKey: String = "before"

  /** The filters of the activity-feed listing, in the order the spec declares them.
    *
    * The date is rendered as `yyyy-MM-dd`, which is what the spec's `format: date` means and what Go's date parsing
    * accepts. A [[java.time.LocalDate]] has no time zone to lose, so unlike
    * [[com.worxbend.codeberg4s.codec.Timestamps.render]] there is nothing to normalise here.
    */
  def activityFeeds(query: ActivityFeedQuery): List[(String, String)] =
    List(
      query.onlyPerformedBy.map(flag => OnlyPerformedByKey -> flag.toString),
      query.date.map(day             => DateKey -> SocialQueries.IsoDate.format(day)),
    ).flatten

  /** The window of the tracked-time listing.
    *
    * `since` and `before` are rendered by [[com.worxbend.codeberg4s.codec.Timestamps.render]] in the RFC-3339 form Go
    * parses — a malformed one comes back as a `422` whose message is the raw Go parse error, per `docs/HAZARDS.md` §4.
    */
  def trackedTimes(window: TrackedTimeWindow): List[(String, String)] =
    List(
      window.since.map(moment  => SinceKey -> Timestamps.render(moment)),
      window.before.map(moment => BeforeKey -> Timestamps.render(moment)),
    ).flatten

  /** `yyyy-MM-dd`, the spec's `format: date`. Immutable and safe to share. */
  private val IsoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
