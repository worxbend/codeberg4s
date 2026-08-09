package com.worxbend.codeberg4s.issues

import scala.concurrent.duration.FiniteDuration

import java.time.Instant

/** One entry in an issue's worked-time log.
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `TrackedTime`. No golden fixture covers it: every timetracking
  * endpoint requires a token and the harvest was anonymous.
  *
  * ==A duration, not a number==
  *
  * The wire field is `time`, an `int64` the spec documents as "Time in seconds". Keeping that as a `Long` in the domain
  * would push the unit into every call site and into every comparison, where it is exactly the kind of thing that gets
  * added to a value in minutes. [[spent]] is therefore a `FiniteDuration`, converted once at the wire boundary, and a
  * caller who wants seconds asks for them.
  *
  * ==Two fields the wire has and this does not==
  *
  * `issue_id` and `user_id` are both marked "deprecated (only for backwards compatibility)" in the spec. The issue is
  * already available in full on [[issue]], and the user's login on [[userName]], so carrying the deprecated pair would
  * offer a caller two ways to ask the same question and one of them would be the one Forgejo intends to remove.
  *
  * @param id
  *   the instance-wide identifier of this entry; the only way to delete one, see [[TrackedTimeId]]
  * @param issue
  *   the issue the time was logged against, absent when the instance sent no issue object — the times listing for a
  *   single issue does not always repeat it
  * @param spent
  *   how long was worked; seconds on the wire, see above
  * @param userName
  *   the login of the account the time is attributed to
  * @param createdAt
  *   when the entry was recorded, which an import may backdate
  */
final case class TrackedTime private[codeberg4s] (
    id: TrackedTimeId,
    issue: Option[Issue],
    spent: FiniteDuration,
    userName: Option[String],
    createdAt: Option[Instant],
)
