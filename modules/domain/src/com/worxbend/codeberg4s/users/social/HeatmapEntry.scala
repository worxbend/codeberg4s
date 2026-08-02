package com.worxbend.codeberg4s.users.social

import java.time.Instant

/** One bucket of `GET /users/{username}/heatmap` — how many contributions an account made in one period.
  *
  * '''Derived from `spec/swagger.v1.json`'s `UserHeatmapData`, not from a captured response.''' The path is marked
  * anonymous in the spec, but the golden harvest did not include it, and `docs/HAZARDS.md` §2 measures that the spec
  * carries no per-operation security information at all — so neither the field set nor the anonymity is backed by a
  * capture here.
  *
  * ==Epoch seconds do not reach the domain==
  *
  * The wire field is `timestamp`, declared as Forgejo's `TimeStamp` type, which the spec defines as a bare `int64`.
  * That is seconds since the Unix epoch — not milliseconds, and not the RFC-3339 string every other timestamp in this
  * API uses. Converting it at the wire boundary is what stops a caller from having to know which of the three spellings
  * this particular endpoint chose, and from multiplying by a thousand in the wrong direction.
  *
  * '''The bucket width is the instance's business.''' Forgejo groups contributions by day in its own web UI but the
  * spec states no width, so [[at]] is the start of whatever period the instance chose and nothing here promises the
  * entries are evenly spaced or contiguous. Days with no contributions are simply absent.
  *
  * @param at
  *   the start of the period, converted from the wire's epoch seconds
  * @param contributions
  *   how many contributions fell in it. Forgejo counts commits, issues, pull requests and reviews; which of those it
  *   counts on a given release is not specified
  */
final case class HeatmapEntry(at: Instant, contributions: Long)
