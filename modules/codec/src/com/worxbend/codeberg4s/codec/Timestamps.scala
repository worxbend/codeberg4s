package com.worxbend.codeberg4s.codec

import scala.util.Try

import java.time.Instant
import java.time.OffsetDateTime

/** Turns Forgejo's timestamp strings into instants, sentinels included.
  *
  * Forgejo renders times as RFC-3339 with an explicit offset — `"2022-11-26T18:56:24+01:00"` on
  * `golden/user/user-single.json`, `"2026-08-01T22:12:04+02:00"` on `golden/repository/repo-single.json`.
  *
  * It also has a second spelling for "never", which the golden-fixture manifest counts: `User.last_login` (104
  * occurrences), `Repository.mirror_updated` (41) and `CommitMeta.created` (4) come back as the Go zero time
  * `"0001-01-01T00:00:00Z"` rather than `null`, and `Repository.archived_at` comes back as the Unix epoch
  * (`"1970-01-01T01:00:00+01:00"`) on repositories that were never archived. Both are absence wearing a costume, and
  * both are folded into `None` here so no caller has to know the trick.
  */
object Timestamps:

  /** Parses a Forgejo timestamp.
    *
    * '''Never throws.''' Answers `None` for a blank string, for anything that is not RFC-3339 with an offset, and for
    * any instant at or before the Unix epoch — see the sentinels above. The epoch boundary means this cannot represent
    * a genuine pre-1970 timestamp; Forgejo has no field that could carry one, since every timestamp it emits describes
    * an event on a Git forge.
    */
  def parse(value: String): Option[Instant] =
    Try(OffsetDateTime.parse(value.trim).toInstant).toOption
      .filter(_.isAfter(Instant.EPOCH))

  /** [[parse]] lifted over an optional wire value, for the common `dto.createdAt.flatMap(...)` shape. */
  def parseOptional(value: Option[String]): Option[Instant] =
    value.flatMap(parse)
