package com.worxbend.codeberg4s.issues.wire

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Renders an instant the way Forgejo parses one.
  *
  * The counterpart of [[com.worxbend.codeberg4s.codec.Timestamps]], which only reads. Reading is lenient and writing
  * cannot be: Forgejo parses timestamps with Go's `time.RFC3339` layout, `2006-01-02T15:04:05Z07:00`, and a value it
  * cannot parse comes back as a `422` whose message is the raw Go parse error — `docs/HAZARDS.md` §4 captures exactly
  * that response for `?since=notadate`.
  *
  * Seconds are the finest unit emitted. `Instant.toString` would append fractional seconds when it has them, which Go
  * does accept, but truncating keeps the rendering stable regardless of where the caller's instant came from, and keeps
  * a `since` cursor byte-identical between runs.
  *
  * Internal to this group's wire package. It is a candidate to move into `com.worxbend.codeberg4s.codec` the moment a
  * second endpoint group needs to '''send''' a timestamp; until then, promoting it would be speculative.
  */
private[codeberg4s] object WireInstant:

  /** `value` as RFC-3339 with a `Z` offset and second precision, for example `"2026-08-01T18:14:16Z"`. */
  def render(value: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS))
