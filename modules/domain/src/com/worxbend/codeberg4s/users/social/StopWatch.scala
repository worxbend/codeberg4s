package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.repositories.RepoSlug

import scala.concurrent.duration.FiniteDuration

import java.time.Instant

/** A running timer on an issue — one entry of `GET /user/stopwatches`.
  *
  * '''Derived from `spec/swagger.v1.json`'s `StopWatch`, not from a captured response''' — the path needs a token and
  * the golden harvest was anonymous.
  *
  * Forgejo lets an account have at most one stopwatch running at a time in its web UI, but the endpoint is a paged
  * listing and nothing in the spec promises at most one element. This model says nothing about that either.
  *
  * ==Seconds become a duration at the boundary==
  *
  * The wire carries `seconds` as an `int64` and `duration` as a pre-formatted human string such as `"1h2m3s"`.
  * [[elapsed]] is the first, converted once at the wire boundary for the reason
  * [[com.worxbend.codeberg4s.issues.TrackedTime.spent]] gives: a raw number of seconds in the domain is a unit waiting
  * to be added to a value in minutes. [[durationText]] is the second, carried verbatim because it is the instance's own
  * rendering and a caller displaying a timer may prefer it to re-deriving one.
  *
  * '''[[elapsed]] is a snapshot, not a live value.''' It is how long the stopwatch had been running when the instance
  * built the response, and it is already stale by the time it arrives.
  *
  * @param issueIndex
  *   the issue's per-repository number, the `#42` a human sees. Required, because a stopwatch that does not say which
  *   issue it is on cannot be stopped
  * @param issueTitle
  *   the issue's title, when the instance sent one
  * @param repository
  *   the repository the issue belongs to, absent when the instance sent an owner or name this library will not turn
  *   into a path — see the note on leniency in the DTO
  * @param elapsed
  *   how long the stopwatch had run when the response was built
  * @param durationText
  *   the instance's own rendering of [[elapsed]], for example `"1h2m3s"`
  * @param createdAt
  *   when the stopwatch was started
  */
final case class StopWatch(
    issueIndex: Long,
    issueTitle: Option[String],
    repository: Option[RepoSlug],
    elapsed: FiniteDuration,
    durationText: Option[String],
    createdAt: Option[Instant],
)
